package com.latestay.booking;

import com.latestay.booking.BookingDtos.BookingPreview;
import com.latestay.booking.BookingDtos.BookingView;
import com.latestay.booking.BookingDtos.ExistingBooking;
import com.latestay.booking.BookingDtos.HotelInfo;
import com.latestay.booking.BookingDtos.NextBest;
import com.latestay.booking.BookingDtos.Refund;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.payment.Payment;
import com.latestay.payment.PaymentRepository;
import com.latestay.stay.LateStayTime;
import com.latestay.stay.SearchService;
import com.latestay.stay.StayRequest;
import com.latestay.stay.StayRequestRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read models for the traveller's pass. */
@Service
public class BookingViews {

    private static final Set<BookingStatus> SHOWS_CODE = Set.of(BookingStatus.HOTEL_CONFIRMED,
            BookingStatus.CHECKED_IN);
    private static final Set<BookingStatus> FELL_THROUGH = Set.of(BookingStatus.HOTEL_DECLINED,
            BookingStatus.HOTEL_TIMEOUT, BookingStatus.REFUNDED, BookingStatus.EXPIRED);

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final StayRequestRepository stayRequests;
    private final SearchService search;
    private final JdbcClient jdbc;
    private final BookingService service;
    private final BookingProperties properties;
    private final java.time.Clock clock;

    public BookingViews(BookingRepository bookings, PaymentRepository payments, HotelRepository hotels,
            RoomTypeRepository roomTypes, StayRequestRepository stayRequests, SearchService search, JdbcClient jdbc,
            BookingService service, BookingProperties properties, java.time.Clock clock) {
        this.clock = clock;
        this.service = service;
        this.properties = properties;
        this.bookings = bookings;
        this.payments = payments;
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.stayRequests = stayRequests;
        this.search = search;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public BookingView view(UUID id) {
        Booking b = bookings.find(id).orElseThrow(() -> new NotFoundException("booking", id));
        Hotel hotel = hotels.find(b.hotelId()).orElseThrow();
        String room = bookings.rooms(id).describe();
        Optional<Payment> payment = payments.forBooking(id);

        return new BookingView(b.id(), b.sessionId(), b.status(),
                hotelInfo(hotel), room, b.guestName(), b.phone(), b.guests(), local(b.arriveAt()), local(b.arriveBy()),
                local(b.leaveAt()), b.agreedPrice(), b.depositAmount(), b.payAtDesk(),
                SHOWS_CODE.contains(b.status()) ? b.code() : null, b.payBy(), b.confirmBy(),
                payment.flatMap(BookingViews::refund).orElse(null),
                FELL_THROUGH.contains(b.status()) ? nextBest(b).orElse(null) : null,
                b.status() == BookingStatus.REFUNDED ? refundedFrom(id) : null, b.disputeReason(),
                new BookingDtos.Actions(
                        b.status() == BookingStatus.PENDING_PAYMENT || b.status() == BookingStatus.DEPOSIT_PAID,
                        b.status() == BookingStatus.HOTEL_CONFIRMED && !clock.instant().isBefore(b.arriveAt()),
                        b.status() == BookingStatus.NO_SHOW && !bookings.everDisputed(id)),
                b.status() == BookingStatus.HOTEL_FAILED ? alternatives(b) : List.of());
    }

    /** "Deluxe", or "2 rooms (Twin + Family)" for a group's negotiation. */
    private String sessionRooms(UUID sessionId) {
        return jdbc.sql("""
                        SELECT count(*) AS n, string_agg(rt.name, ' + ' ORDER BY nr.line_no) AS names
                        FROM negotiation_room nr JOIN room_type rt ON rt.id = nr.room_type_id
                        WHERE nr.session_id = :id""")
                .param("id", sessionId)
                .query((rs, n) -> new BookingRepository.Rooms(Math.max(1, rs.getInt("n")),
                        rs.getString("names") == null ? "Room" : rs.getString("names")).describe())
                .single();
    }

    /** After a Rescue: the three nearest hotels with rooms for a search arriving now. */
    private List<NextBest> alternatives(Booking b) {
        return bookings.rescueSearchOf(b.id()).flatMap(stayRequests::find)
                .map(request -> search.results(request).stream()
                        .filter(h -> !h.id().equals(b.hotelId()))
                        .limit(3)
                        .map(h -> new NextBest(h.slug(), h.name(), h.town(),
                                h.photos().isEmpty() ? null : h.photos().getFirst(), h.distanceKm(), h.fromPrice(),
                                request.id()))
                        .toList())
                .orElse(List.of());
    }

    private BookingStatus refundedFrom(UUID id) {
        return bookings.events(id).stream().filter(e -> e.to() == BookingStatus.REFUNDED).map(BookingEvent::from)
                .reduce((first, second) -> second).orElse(null);
    }

    /** The booking page before payment. 409 unless the negotiation reached an agreed price. */
    @Transactional(readOnly = true)
    public BookingPreview preview(UUID sessionId) {
        var session = bookings.findSession(sessionId).orElseThrow(() -> new NotFoundException("negotiation", sessionId));
        if (!"AGREED".equals(session.status()) || session.agreedPrice() == null) {
            throw new ConflictException("no price has been agreed in this negotiation");
        }
        Hotel hotel = hotels.find(session.hotelId()).orElseThrow();
        StayRequest stay = stayRequests.find(session.stayRequestId()).orElseThrow();
        Deposit deposit = Deposit.of(session.agreedPrice(), properties.depositCap(), properties.commission());
        return new BookingPreview(sessionId, hotelInfo(hotel),
                sessionRooms(session.id()), stay.window().arrive(),
                local(service.arriveBy(stay.window())), stay.window().leave(), stay.guests(), deposit.agreedPrice(),
                deposit.deposit(), deposit.payAtDesk(),
                bookings.liveForSession(sessionId).map(b -> new ExistingBooking(b.id(), b.status())).orElse(null));
    }

    private static HotelInfo hotelInfo(Hotel hotel) {
        return new HotelInfo(hotel.id(), hotel.slug(), hotel.name(), hotel.town(), hotel.address(),
                hotel.photos().isEmpty() ? null : hotel.photos().getFirst(), hotel.whatsappNumber(), hotel.lat(),
                hotel.lng());
    }

    private static Optional<Refund> refund(Payment p) {
        return switch (p.status()) {
            case REFUND_PENDING, REFUND_REQUESTED -> Optional.of(new Refund(p.amount(), "PENDING"));
            case REFUNDED -> Optional.of(new Refund(p.amount(), "PROCESSED"));
            case CREATED, CAPTURED -> Optional.empty();
        };
    }

    /** The original search, run again without this hotel: the nearest other hotel with rooms. */
    private Optional<NextBest> nextBest(Booking b) {
        UUID stayRequestId = jdbc.sql("SELECT stay_request_id FROM negotiation_session WHERE id = :id")
                .param("id", b.sessionId()).query(UUID.class).single();
        return stayRequests.find(stayRequestId)
                .flatMap(request -> search.results(request).stream()
                        .filter(h -> !h.id().equals(b.hotelId()))
                        .findFirst()
                        .map(h -> new NextBest(h.slug(), h.name(), h.town(),
                                h.photos().isEmpty() ? null : h.photos().getFirst(), h.distanceKm(), h.fromPrice(),
                                stayRequestId)));
    }

    static LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, LateStayTime.ZONE);
    }
}
