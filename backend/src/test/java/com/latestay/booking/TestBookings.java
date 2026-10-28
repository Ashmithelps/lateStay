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
        TestHotels hotels = context.getBean(TestHotels.class);
        double lat = baseLat + NEXT_SPOT.getAndIncrement() * 0.2;
        Hotel hotel = hotels.hotel("Booking Lodge", lat, 79.4, List.of("heater"),
                new HotelPolicies(true, true, null), true);
        RoomType room = hotels.room(hotel, listPrice, 3, floorPrice, SlotType.LATE_NIGHT);
        hotels.roomsFree(hotel, NIGHT, rooms);
        StayRequest stay = context.getBean(StayRequestRepository.class)
                .insert(lat, 79.4, StayWindow.of(ARRIVE, LEAVE), 2, List.of());
        UUID sessionId = context.getBean(NegotiationService.class).create(stay.id(), room.id()).id();
        var reply = context.getBean(ConversationService.class).accept(sessionId.toString());
        return new Agreed(hotel, room, stay, sessionId, reply.offer());
    }

    public BookingCreated book(Agreed agreed) {
        return context.getBean(BookingService.class)
                .create(new CreateBookingRequest(agreed.sessionId(), "Asha Verma", "98765 43210", true));
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
