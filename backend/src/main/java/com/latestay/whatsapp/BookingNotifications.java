package com.latestay.whatsapp;

import com.latestay.booking.Booking;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.BookingStatusChanged;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.stay.LateStayTime;
import java.time.LocalDateTime;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the owner about bookings: booking_request once the deposit is paid, booking_cancelled when a paid booking
 * is cancelled or times out before they answered. Runs inside the booking's transaction (queue only).
 */
@Component
class BookingNotifications {

    private final WhatsAppOutbox outbox;
    private final HotelRepository hotels;
    private final WhatsAppProperties properties;

    BookingNotifications(WhatsAppOutbox outbox, HotelRepository hotels, WhatsAppProperties properties) {
        this.outbox = outbox;
        this.hotels = hotels;
        this.properties = properties;
    }

    @EventListener
    void on(BookingStatusChanged change) {
        Booking b = change.booking();
        if (change.to() == BookingStatus.DEPOSIT_PAID) {
            Hotel hotel = hotels.find(b.hotelId()).orElseThrow();
            outbox.queue(hotel.id(), WhatsAppMessages.bookingRequest(WhatsAppOutbox.waId(hotel.whatsappNumber()),
                            properties.language(), b.id(), b.guests(),
                            LocalDateTime.ofInstant(b.arriveAt(), LateStayTime.ZONE).toLocalTime(), b.payAtDesk(),
                            b.depositAmount(), b.code()),
                    b.id(), WhatsAppMessages.BOOKING_REQUEST + ":" + b.id());
        }
        boolean wasWaiting = change.from() == BookingStatus.DEPOSIT_PAID;
        if (wasWaiting && (change.to() == BookingStatus.CANCELLED || change.to() == BookingStatus.HOTEL_TIMEOUT)) {
            Hotel hotel = hotels.find(b.hotelId()).orElseThrow();
            String reason = change.to() == BookingStatus.CANCELLED
                    ? "the guest cancelled"
                    : "it wasn't confirmed within 5 minutes";
            outbox.queue(hotel.id(), WhatsAppMessages.bookingCancelled(WhatsAppOutbox.waId(hotel.whatsappNumber()),
                    properties.language(), b.code(), reason), b.id(), WhatsAppMessages.BOOKING_CANCELLED + ":" + b.id());
        }
    }
}
