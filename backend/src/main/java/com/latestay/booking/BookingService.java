package com.latestay.booking;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingDtos.Checkout;
import com.latestay.booking.BookingDtos.CreateBookingRequest;
import com.latestay.booking.BookingRepository.AgreedSession;
import com.latestay.hold.RoomHolds;
import com.latestay.ledger.Ledger;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.NotFoundException;
import com.latestay.payment.Payment;
import com.latestay.payment.PaymentGatewayException;
import com.latestay.payment.PaymentRepository;
import com.latestay.payment.PaymentStatus;
import com.latestay.payment.RazorpayGateway;
import com.latestay.phone.PhoneHashes;
import com.latestay.phone.PhoneNotVerifiedException;
import com.latestay.stay.LateStayTime;
import com.latestay.stay.StayRequest;
import com.latestay.stay.InvalidStayException;
import com.latestay.stay.SlotTimes;
import com.latestay.stay.StayRequestRepository;
import com.latestay.stay.StayWindow;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bookings from agreed negotiations, their deposits and every status change. Each change locks the booking row and
 * goes through {@link BookingTransitions#guard}. Amounts come from the session's agreed price, never the client.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final StayRequestRepository stayRequests;
    private final RazorpayGateway gateway;
    private final BookingProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final RoomHolds holds;
    private final PhoneHashes phoneHashes;
    private final Ledger ledger;
    private final com.latestay.promo.Promos promos;

    public BookingService(BookingRepository bookings, PaymentRepository payments, StayRequestRepository stayRequests,
            RazorpayGateway gateway, BookingProperties properties, Clock clock, ApplicationEventPublisher events,
            RoomHolds holds, PhoneHashes phoneHashes, Ledger ledger, com.latestay.promo.Promos promos) {
        this.ledger = ledger;
        this.promos = promos;
        this.events = events;
        this.holds = holds;
        this.phoneHashes = phoneHashes;
        this.bookings = bookings;
        this.payments = payments;
        this.stayRequests = stayRequests;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    // Traveller

    /**
     * Creates a booking and its Razorpay order, taking over the negotiation's room hold (or taking a new one if it
     * ran out). Idempotent per negotiation: while a booking for it is unpaid, the same booking and order come back
     * (with the guest details updated); once paid, it comes back without a checkout.
     *
     * @param verifiedPhoneHash the phone hash from this device's verification cookie, or null
     * @throws PhoneNotVerifiedException unless it matches the number given
     */
    @Transactional
    public BookingCreated create(CreateBookingRequest request, String verifiedPhoneHash) {
        String name = request.guestName().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new InvalidBookingException("enter the name the booking is under");
        }
        String phone = IndianMobile.normalise(request.phone())
                .orElseThrow(() -> new InvalidBookingException("enter a 10-digit Indian mobile number"));
        if (!request.idConsent()) {
            throw new InvalidBookingException("every guest needs a photo ID at check-in");
        }
        if (verifiedPhoneHash == null || !verifiedPhoneHash.equals(phoneHashes.phone(phone))) {
            throw new PhoneNotVerifiedException();
        }

        AgreedSession session = bookings.lockSession(request.sessionId())
                .orElseThrow(() -> new NotFoundException("negotiation", request.sessionId()));
        if (!"AGREED".equals(session.status()) || session.agreedPrice() == null) {
            throw new ConflictException("no price has been agreed in this negotiation");
        }
        Instant now = clock.instant();

        var existing = bookings.liveForSession(session.id());
        if (existing.isPresent()) {
            Booking booking = bookings.lock(existing.get().id()).orElseThrow();
            boolean unpaid = booking.status() == BookingStatus.PENDING_PAYMENT;
            boolean samePromo = java.util.Objects.equals(promos.codeOf(booking.id()).orElse(null), request.promo());
            if (unpaid && booking.payBy().isAfter(now) && samePromo) {
                booking = booking.withGuest(name, phone, now);
                bookings.update(booking);
                bookings.whatsappUpdates(booking.id(), request.wantsWhatsAppUpdates() ? now : null);
                Payment payment = payments.forBooking(booking.id()).orElseThrow();
                return created(booking, payment, now);
            } else if (unpaid && booking.payBy().isAfter(now)) {
                // A promo code added (or changed) before paying: this unpaid booking makes way for a new one.
                move(booking, BookingStatus.CANCELLED, Actor.TRAVELLER);
            } else if (unpaid) {
                move(booking, BookingStatus.EXPIRED, Actor.JOB);
            } else {
                return new BookingCreated(booking.id(), booking.status(), booking.payBy(), null);
            }
        }

        StayRequest stay = stayRequests.find(session.stayRequestId())
                .orElseThrow(() -> new NotFoundException("stay request", session.stayRequestId()));
        StayWindow window = stay.window();
        Instant arriveAt = window.arrive().atZone(LateStayTime.ZONE).toInstant();
        Instant leaveAt = window.leave().atZone(LateStayTime.ZONE).toInstant();
        Instant arriveBy = arriveBy(window);
        if (!now.isBefore(arriveBy)) {
            throw new ConflictException("the arrival time for this stay has passed");
        }
        RoomHolds.Hold hold = holds.take(session.id())
                .orElseThrow(() -> new ConflictException("the hotel has no rooms left for this night"));

        Deposit deposit = Deposit.of(session.agreedPrice(), properties.depositCap(), properties.commission());
        // A promo code comes off what's paid online now; the desk amount stays the same.
        long discount = request.promo() == null ? 0 : promos.preview(request.promo(), deposit.deposit()).discountRupees();
        Booking booking = new Booking(UUID.randomUUID(), session.id(), session.hotelId(), session.roomTypeId(),
                window.nightOf(), name, phone, stay.guests(), arriveAt, arriveBy, leaveAt, deposit.agreedPrice(),
                deposit.deposit() - discount, deposit.payAtDesk(), deposit.commission(),
                newCode(bookings.codesInUse(session.hotelId(), window.nightOf())), BookingStatus.PENDING_PAYMENT,
                now.plus(properties.paymentWindow()), null, null, null, null, null, now, now, null, null, null, null,
                null, hold.id());
        bookings.insert(booking, discount * 100);
        bookings.copyAttribution(booking.id(), session.stayRequestId());
        if (request.promo() != null) {
            promos.reserve(request.promo(), booking.id(), verifiedPhoneHash, deposit.deposit());
        }
        if (request.wantsWhatsAppUpdates()) {
            bookings.whatsappUpdates(booking.id(), now);
        }
        holds.convert(hold.id(), booking.id());
        bookings.event(booking.id(), null, BookingStatus.PENDING_PAYMENT, Actor.TRAVELLER, now);

        RazorpayGateway.Order order = gateway.createOrder(booking.depositAmount(), booking.id().toString());
        Payment payment = payments.insert(booking.id(), order.id(), booking.depositAmount(), now);
        return created(booking, payment, now);
    }

    /** Razorpay Checkout's success callback, checked against the order's signature. */
    @Transactional
    public BookingStatus verify(UUID bookingId, String orderId, String paymentId, String signature) {
        Payment payment = payments.lockByOrderId(orderId)
                .filter(p -> p.bookingId().equals(bookingId))
                .orElseThrow(() -> new InvalidBookingException("this payment isn't for this booking"));
        if (!gateway.checkoutSignatureValid(orderId, paymentId, signature)) {
            throw new InvalidBookingException("the payment couldn't be verified");
        }
        captured(payment, paymentId, null, Actor.TRAVELLER);
        return bookings.find(bookingId).orElseThrow().status();
    }

    /**
     * Before the hotel confirms, the traveller can cancel: an unpaid booking just closes, a paid one is refunded in
     * full.
     */
    @Transactional
    public Booking cancel(UUID id) {
        Booking booking = lock(id);
        Booking cancelled = move(booking, BookingStatus.CANCELLED, Actor.TRAVELLER);
        if (booking.status() == BookingStatus.DEPOSIT_PAID) {
            requestRefund(id);
        }
        return cancelled;
    }

    // Hotel (through the admin panel for now)

    @Transactional
    public Booking confirm(UUID id) {
        return confirm(id, Actor.ADMIN);
    }

    /** @param actor ADMIN from the panel, HOTEL from the owner's WhatsApp button */
    @Transactional
    public Booking confirm(UUID id, Actor actor) {
        return move(lock(id), BookingStatus.HOTEL_CONFIRMED, actor);
    }

    @Transactional
    public Booking decline(UUID id) {
        return decline(id, Actor.ADMIN);
    }

    @Transactional
    public Booking decline(UUID id, Actor actor) {
        Booking declined = move(lock(id), BookingStatus.HOTEL_DECLINED, actor);
        requestRefund(id);
        return declined;
    }

    /** The desk checks the traveller's 4-digit code. */
    @Transactional
    public Booking checkIn(UUID id, String code) {
        Booking booking = lock(id);
        BookingTransitions.guard(booking.status(), BookingStatus.CHECKED_IN);
        if (code == null || !booking.code().equals(code.trim())) {
            throw new InvalidBookingException("that code doesn't match this booking");
        }
        return move(booking, BookingStatus.CHECKED_IN, Actor.ADMIN);
    }

    /** The owner's Arrived button, or the owner typing the desk code (also after saying Didn't arrive). */
    @Transactional
    public Booking checkInByOwner(UUID id) {
        return move(lock(id), BookingStatus.CHECKED_IN, Actor.HOTEL);
    }

    /** The owner's Didn't arrive button. The deposit is kept, unless the traveller disputes it. */
    @Transactional
    public Booking noShowByOwner(UUID id) {
        return move(lock(id), BookingStatus.NO_SHOW, Actor.HOTEL);
    }

    // Traveller: disputes

    /** "I did arrive" after the owner said they didn't. Once per booking; an admin settles it. */
    @Transactional
    public Booking travellerArrived(UUID id) {
        Booking booking = lock(id);
        BookingTransitions.guard(booking.status(), BookingStatus.DISPUTED);
        if (booking.status() != BookingStatus.NO_SHOW || bookings.everDisputed(id)) {
            throw new IllegalTransitionException(booking.status(), BookingStatus.DISPUTED);
        }
        Booking disputed = move(booking.withDisputeReason(Booking.TRAVELLER_ARRIVED), BookingStatus.DISPUTED,
                Actor.TRAVELLER);
        events.publishEvent(new AdminAlert("guest says they did arrive", disputed));
        return disputed;
    }

    /**
     * "The hotel turned me away", from the arrival time on: DISPUTED, the room goes back (and the hotel leaves
     * search for the night until an admin settles it), and the admin gets a WhatsApp alert.
     */
    @Transactional
    public Booking turnedAway(UUID id) {
        Booking booking = lock(id);
        if (booking.status() != BookingStatus.HOTEL_CONFIRMED) {
            throw new IllegalTransitionException(booking.status(), BookingStatus.DISPUTED);
        }
        if (clock.instant().isBefore(booking.arriveAt())) {
            throw new InvalidBookingException("you can report this from your arrival time");
        }
        Booking disputed = move(booking.withDisputeReason(Booking.TURNED_AWAY), BookingStatus.DISPUTED,
                Actor.TRAVELLER);
        bookings.hideUntil(id, clock.instant().plus(properties.disputeHide()));
        events.publishEvent(new AdminAlert("guest turned away at the desk", disputed));
        return disputed;
    }

    // Admin: disputes and rescue

    /** "Keep hidden": the hotel stays out of search for that night until the dispute is settled. */
    @Transactional
    public Booking keepHidden(UUID id) {
        Booking booking = lock(id);
        if (booking.status() != BookingStatus.DISPUTED || !Booking.TURNED_AWAY.equals(booking.disputeReason())) {
            throw new ConflictException("only an open \"turned away\" dispute can keep its hotel hidden");
        }
        bookings.keepHidden(id, clock.instant());
        return booking;
    }

    @Transactional
    public Booking settle(UUID id, DisputeOutcome outcome) {
        Booking booking = lock(id);
        if (booking.status() != BookingStatus.DISPUTED) {
            throw new IllegalTransitionException(booking.status(),
                    outcome == DisputeOutcome.GUEST_STAYED ? BookingStatus.COMPLETED : BookingStatus.NO_SHOW);
        }
        return move(booking, outcome == DisputeOutcome.GUEST_STAYED ? BookingStatus.COMPLETED
                : BookingStatus.NO_SHOW, Actor.ADMIN);
    }

    /**
     * The hotel let the traveller down: HOTEL_FAILED, the deposit refunded straight away, one more failure on the
     * hotel's record, and a fresh search (same place and guests, arriving now) for the traveller's alternatives.
     */
    @Transactional
    public Booking rescue(UUID id) {
        Booking failed = move(lock(id), BookingStatus.HOTEL_FAILED, Actor.ADMIN);
        bookings.countHotelFailure(failed.hotelId());
        requestRefund(id);
        payments.lockForBooking(id).filter(p -> p.status() == PaymentStatus.REFUND_PENDING)
                .ifPresent(this::refundNow);
        rescueSearch(failed).ifPresent(stayRequestId -> bookings.rescueSearch(id, stayRequestId));
        return failed;
    }

    /** A search like the original but arriving now (next quarter hour), if now is inside a stay slot. */
    private java.util.Optional<UUID> rescueSearch(Booking booking) {
        UUID sessionStay = bookings.stayRequestOf(booking.sessionId());
        StayRequest original = stayRequests.find(sessionStay).orElseThrow();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), LateStayTime.ZONE);
        try {
            StayWindow window = SlotTimes.arrivingNow(now, original.window().leave());
            return java.util.Optional.of(stayRequests.insert(original.lat(), original.lng(), window, original.guests(),
                    original.filters()).id());
        } catch (InvalidStayException e) {
            log.info("No rescue search for booking {}: {}", booking.id(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    // Razorpay

    /**
     * A captured deposit, from Checkout or the payment.captured webhook. Idempotent: a payment already recorded is
     * ignored. A payment that lands after the booking expired or was cancelled is refunded.
     */
    @Transactional
    public void paymentCaptured(String orderId, String paymentId, long amount, String rawEvent) {
        payments.lockByOrderId(orderId).ifPresentOrElse(payment -> {
            if (payment.amount() != amount) {
                log.warn("Ignoring capture of {} for order {}: expected {}", amount, orderId, payment.amount());
                return;
            }
            captured(payment, paymentId, rawEvent, Actor.RAZORPAY);
        }, () -> log.warn("payment.captured for unknown order {}", orderId));
    }

    /** refund.processed: the money is back with the traveller. Idempotent. */
    @Transactional
    public void refundProcessed(String paymentId, String refundId, String rawEvent) {
        payments.lockByPaymentId(paymentId).ifPresentOrElse(
                payment -> refunded(payment, refundId, rawEvent),
                () -> log.warn("refund.processed for unknown payment {}", paymentId));
    }

    private void captured(Payment payment, String paymentId, String rawEvent, Actor actor) {
        Instant now = clock.instant();
        if (payment.status() != PaymentStatus.CREATED) {
            if (rawEvent != null && paymentId.equals(payment.paymentId())) {
                payments.rawEvent(payment.id(), rawEvent, now);
            }
            return;
        }
        payments.captured(payment.id(), paymentId, rawEvent, now);
        Booking booking = lock(payment.bookingId());
        if (booking.status() == BookingStatus.PENDING_PAYMENT) {
            move(booking, BookingStatus.DEPOSIT_PAID, actor);
        } else {
            log.info("Deposit for booking {} arrived in {}; refunding", booking.id(), booking.status());
            payments.status(payment.id(), PaymentStatus.REFUND_PENDING, now);
        }
    }

    private void refunded(Payment payment, String refundId, String rawEvent) {
        Instant now = clock.instant();
        if (payment.status() == PaymentStatus.REFUNDED) {
            return;
        }
        payments.refunded(payment.id(), refundId, rawEvent, now);
        Booking booking = lock(payment.bookingId());
        if (booking.status().awaitsRefund()) {
            move(booking, BookingStatus.REFUNDED, Actor.RAZORPAY);
        }
    }

    private void requestRefund(UUID bookingId) {
        ledger.refunded(bookingId, "deposit refunded to the guest");
        payments.lockForBooking(bookingId)
                .filter(p -> p.status() == PaymentStatus.CAPTURED)
                .ifPresent(p -> payments.status(p.id(), PaymentStatus.REFUND_PENDING, clock.instant()));
    }

    // Jobs

    /** Unpaid when the payment window closes. */
    @Transactional
    public boolean expireIfUnpaid(UUID id) {
        Booking booking = lock(id);
        if (booking.status() != BookingStatus.PENDING_PAYMENT || booking.payBy().isAfter(clock.instant())) {
            return false;
        }
        move(booking, BookingStatus.EXPIRED, Actor.JOB);
        return true;
    }

    /** Paid but the hotel didn't answer in time: refund. */
    @Transactional
    public boolean timeoutIfUnconfirmed(UUID id) {
        Booking booking = lock(id);
        if (booking.status() != BookingStatus.DEPOSIT_PAID || booking.confirmBy().isAfter(clock.instant())) {
            return false;
        }
        move(booking, BookingStatus.HOTEL_TIMEOUT, Actor.JOB);
        requestRefund(id);
        return true;
    }

    /**
     * An hour after arrive_by, ask the owner whether the guest arrived. The booking stays HOTEL_CONFIRMED; if
     * nobody answers by {@code complete_by} (10:00 the next morning, and at least 2 hours after asking) it
     * completes on its own.
     */
    @Transactional
    public boolean sendArrivalCheckIfDue(UUID id) {
        Booking booking = lock(id);
        Instant now = clock.instant();
        if (booking.status() != BookingStatus.HOTEL_CONFIRMED || booking.arrivalCheckSentAt() != null
                || booking.arriveBy().plus(properties.arrivalCheckAfter()).isAfter(now)) {
            return false;
        }
        Instant nextMorning = booking.nightOf().plusDays(1).atTime(10, 0).atZone(LateStayTime.ZONE).toInstant();
        Instant earliest = now.plus(properties.autoCompleteMinimum());
        Booking asked = booking.withArrivalCheck(now, nextMorning.isAfter(earliest) ? nextMorning : earliest);
        bookings.update(asked);
        events.publishEvent(new ArrivalCheckDue(asked));
        return true;
    }

    /** Nobody answered the arrival check: we assume the guest arrived and the deposit goes to the hotel. */
    @Transactional
    public boolean completeIfUnanswered(UUID id) {
        Booking booking = lock(id);
        if (booking.status() != BookingStatus.HOTEL_CONFIRMED || booking.completeBy() == null
                || booking.completeBy().isAfter(clock.instant())) {
            return false;
        }
        move(booking, BookingStatus.COMPLETED, Actor.JOB);
        return true;
    }

    /** Housekeeping for holds that ran out (they already stopped counting). */
    @Transactional
    public int expireHolds() {
        return holds.expireOld();
    }

    @Transactional
    public boolean completeIfLeft(UUID id) {
        Booking booking = lock(id);
        if (booking.status() != BookingStatus.CHECKED_IN || booking.leaveAt().isAfter(clock.instant())) {
            return false;
        }
        move(booking, BookingStatus.COMPLETED, Actor.JOB);
        return true;
    }

    /**
     * Calls Razorpay for payments waiting for a refund. A failed call stays pending for the next run. Returns how
     * many refunds Razorpay accepted.
     */
    @Transactional
    public int issueRefunds(int limit) {
        int issued = 0;
        for (Payment payment : payments.lockRefundsPending(limit)) {
            if (refundNow(payment)) {
                issued++;
            }
        }
        return issued;
    }

    /** One refund call; a failure stays REFUND_PENDING for the refund job. Returns whether Razorpay accepted it. */
    private boolean refundNow(Payment payment) {
        Instant now = clock.instant();
        try {
            RazorpayGateway.Refund refund = gateway.refund(payment.paymentId(), payment.amount(),
                    payment.bookingId().toString());
            payments.refundRequested(payment.id(), refund.id(), now);
            if (refund.processed()) {
                refunded(payments.lockByPaymentId(payment.paymentId()).orElseThrow(), refund.id(), null);
            }
            return true;
        } catch (PaymentGatewayException e) {
            log.warn("Refund for booking {} failed (attempt {}): {}", payment.bookingId(),
                    payment.refundAttempts() + 1, e.getMessage());
            payments.refundFailed(payment.id(), e.getMessage(), now);
            return false;
        }
    }

    /** Privacy: phone numbers go {@code phoneRetention} after the stay ends. */
    @Transactional
    public int deleteOldPhoneNumbers() {
        Instant now = clock.instant();
        return bookings.deletePhonesBefore(now.minus(properties.phoneRetention()), now);
    }

    // Helpers

    public List<UUID> arrivalChecksDue(Duration after) {
        return bookings.arrivalChecksDue(clock.instant().minus(after));
    }

    public List<UUID> due(BookingStatus status, String column, Duration after) {
        return bookings.due(status, column, clock.instant().minus(after));
    }

    private Booking lock(UUID id) {
        return bookings.lock(id).orElseThrow(() -> new NotFoundException("booking", id));
    }

    /** The one place a booking's status changes. */
    private Booking move(Booking booking, BookingStatus to, Actor actor) {
        BookingTransitions.guard(booking.status(), to);
        Instant now = clock.instant();
        Booking moved = booking.movedTo(to, now, properties);
        bookings.update(moved);
        bookings.event(booking.id(), booking.status(), to, actor, now);
        if (booking.status().isLive() && !to.isLive() && to != BookingStatus.COMPLETED) {
            holds.releaseForBooking(booking.id());
        }
        if (to == BookingStatus.CHECKED_IN || to == BookingStatus.COMPLETED || to == BookingStatus.NO_SHOW) {
            ledger.owed(moved.hotelId(), moved.id(), moved.depositAmount(), moved.commissionAmount(),
                    bookings.promoDiscountPaise(moved.id()));
        } else if (to == BookingStatus.HOTEL_FAILED) {
            ledger.refunded(moved.id(), "hotel failed the booking (Rescue)");
        }
        events.publishEvent(new BookingStatusChanged(moved, booking.status(), to, actor));
        return moved;
    }

    private BookingCreated created(Booking booking, Payment payment, Instant now) {
        long timeout = Math.max(60, Duration.between(now, booking.payBy()).toSeconds());
        return new BookingCreated(booking.id(), booking.status(), booking.payBy(),
                new Checkout(gateway.mode(), gateway.keyId(), payment.orderId(), payment.amount(),
                        payment.amount() * 100, "INR", booking.guestName(), booking.phone(), timeout));
    }

    /** Stated arrival plus the grace period, but no later than the slot's last arrival time or the leave time. */
    Instant arriveBy(StayWindow window) {
        LocalDateTime arrive = window.arrive();
        LocalDateTime windowEnd = arrive.toLocalDate().atTime(window.slotType().arriveUntil());
        if (windowEnd.isBefore(arrive)) {
            windowEnd = windowEnd.plusDays(1);
        }
        LocalDateTime by = arrive.plus(properties.arrivalGrace());
        if (by.isAfter(windowEnd)) {
            by = windowEnd;
        }
        if (by.isAfter(window.leave())) {
            by = window.leave();
        }
        return by.atZone(LateStayTime.ZONE).toInstant();
    }

    static String newCode(Set<String> inUse) {
        if (inUse.size() >= 10_000) {
            throw new ConflictException("no desk codes left for this hotel tonight");
        }
        String code;
        do {
            code = "%04d".formatted(RANDOM.nextInt(10_000));
        } while (inUse.contains(code));
        return code;
    }
}
