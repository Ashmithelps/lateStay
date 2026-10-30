package com.latestay.booking;

import com.latestay.TestHotels;
import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingDtos.CreateBookingRequest;
import com.latestay.conversation.ConversationService;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.RoomType;
import com.latestay.negotiation.SlotType;
import com.latestay.payment.FakeRazorpayGateway;
import com.latestay.session.NegotiationService;
import com.latestay.stay.StayRequest;
import com.latestay.stay.StayRequestRepository;
import com.latestay.stay.StayWindow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ApplicationContext;

/**
 * Builds agreed negotiations and bookings for tests: a hotel of its own (spaced ~22 km apart), a 23:30-09:00 stay
 * on the night of 11 Nov 2026, and the opening offer accepted.
 */
public class TestBookings {

    public static final LocalDate NIGHT = LocalDate.of(2026, 11, 11);
    public static final LocalDateTime ARRIVE = NIGHT.atTime(23, 30);
    public static final LocalDateTime LEAVE = NIGHT.plusDays(1).atTime(9, 0);
    private static final AtomicInteger NEXT_SPOT = new AtomicInteger();

    public record Agreed(Hotel hotel, RoomType room, StayRequest stay, UUID sessionId, long price) {
    }

    private final ApplicationContext context;

    TestBookings(ApplicationContext context) {
        this.context = context;
    }

    /** A hotel with {@code rooms} free whose room opens at {@code listPrice}; returns the accepted negotiation. */
    public Agreed agreed(double baseLat, long listPrice, long floorPrice, int rooms) {
        return agreed(baseLat, listPrice, floorPrice, rooms, ARRIVE, LEAVE);
    }

    /** As above, for another stay (e.g. an early-morning arrival). Rooms are reported for the stay's night. */
    public Agreed agreed(double baseLat, long listPrice, long floorPrice, int rooms, LocalDateTime arrive,
            LocalDateTime leave) {
        Opened opened = opened(baseLat, listPrice, floorPrice, rooms, arrive, leave);
        var reply = context.getBean(ConversationService.class).accept(opened.sessionId().toString());
        return new Agreed(opened.hotel(), opened.room(), opened.stay(), opened.sessionId(), reply.offer());
    }

    public record Opened(Hotel hotel, RoomType room, StayRequest stay, UUID sessionId) {
    }

    /** A hotel and an open (not yet agreed) negotiation for the stay. */
    public Opened opened(double baseLat, long listPrice, long floorPrice, int rooms, LocalDateTime arrive,
            LocalDateTime leave) {
        TestHotels hotels = context.getBean(TestHotels.class);
        double lat = baseLat + NEXT_SPOT.getAndIncrement() * 0.2;
        Hotel hotel = hotels.hotel("Booking Lodge", lat, 79.4, List.of("heater"),
                new HotelPolicies(true, true, null), true);
        StayWindow window = StayWindow.of(arrive, leave);
        RoomType room = hotels.room(hotel, listPrice, 3, floorPrice, window.slotType());
        hotels.roomsFree(hotel, window.nightOf(), rooms);
        StayRequest stay = context.getBean(StayRequestRepository.class).insert(lat, 79.4, window, 2, List.of());
        UUID sessionId = context.getBean(NegotiationService.class).create(stay.id(), room.id()).id();
        return new Opened(hotel, room, stay, sessionId);
    }

    /** Another traveller's open negotiation for the same hotel, room and stay. */
    public UUID openAnother(Hotel hotel, RoomType room, StayRequest stay) {
        StayRequest theirs = context.getBean(StayRequestRepository.class)
                .insert(hotel.lat(), hotel.lng(), stay.window(), stay.guests(), List.of());
        return context.getBean(NegotiationService.class).create(theirs.id(), room.id()).id();
    }

    /** Another traveller's accepted negotiation for the same hotel, room and stay. */
    public Agreed agreedAnother(Agreed first) {
        UUID session = openAnother(first.hotel(), first.room(), first.stay());
        var reply = context.getBean(ConversationService.class).accept(session.toString());
        return new Agreed(first.hotel(), first.room(), first.stay(), session, reply.offer());
    }

    public static final String PHONE = "+919876543210";

    /** Books with the number verified on this "device" (what the phone cookie proves in a real request). */
    public BookingCreated book(Agreed agreed) {
        return context.getBean(BookingService.class)
                .create(new CreateBookingRequest(agreed.sessionId(), "Asha Verma", "98765 43210", true),
                        verifiedPhoneHash(PHONE));
    }

    /** As {@link #book}, with "Send my booking pass and updates on WhatsApp" ticked. */
    public BookingCreated bookWithUpdates(Agreed agreed) {
        return context.getBean(BookingService.class)
                .create(new CreateBookingRequest(agreed.sessionId(), "Asha Verma", "98765 43210", true, true),
                        verifiedPhoneHash(PHONE));
    }

    /** Books with a promo code, as the traveller with this 10-digit mobile number (verified on this device). */
    public BookingCreated bookWithPromo(Agreed agreed, String mobile, String promoCode) {
        return context.getBean(BookingService.class)
                .create(new CreateBookingRequest(agreed.sessionId(), "Asha Verma", mobile, true, null, promoCode),
                        verifiedPhoneHash("+91" + mobile));
    }

    public String verifiedPhoneHash(String e164) {
        return context.getBean(com.latestay.phone.PhoneHashes.class).phone(e164);
    }

    /** The "phone verified on this device" cookie, as the API would set it. */
    public jakarta.servlet.http.Cookie phoneCookie(String e164) {
        var cookie = context.getBean(com.latestay.phone.PhoneCookies.class).issue(verifiedPhoneHash(e164));
        return new jakarta.servlet.http.Cookie(cookie.getName(), cookie.getValue());
    }

    /** Pays the booking's order the way Checkout's success handler would. */
    public BookingStatus pay(BookingCreated created) {
        String paymentId = paymentIdFor(created);
        return context.getBean(BookingService.class).verify(created.bookingId(), created.checkout().orderId(),
                paymentId, FakeRazorpayGateway.sign(created.checkout().orderId(), paymentId));
    }

    public static String paymentIdFor(BookingCreated created) {
        return "pay_" + created.checkout().orderId().substring("order_fake_".length());
    }
}
