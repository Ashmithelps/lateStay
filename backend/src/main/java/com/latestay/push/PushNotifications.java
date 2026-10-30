package com.latestay.push;

import com.latestay.booking.Booking;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.BookingStatusChanged;
import com.latestay.hotel.HotelRepository;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the traveller's app about their booking: confirmed (the code is ready), declined, not confirmed in time,
 * cancelled after payment, rescued, refunded. Queued in the booking's transaction; nothing when no device is
 * registered for it. Texts never include the desk code, a phone number or a name.
 */
@Component
class PushNotifications {

    private final PushOutbox outbox;
    private final HotelRepository hotels;

    PushNotifications(PushOutbox outbox, HotelRepository hotels) {
        this.outbox = outbox;
        this.hotels = hotels;
    }

    @EventListener
    void on(BookingStatusChanged change) {
        String text = text(change, "{hotel}");
        if (text == null) {
            return;
        }
        Booking b = change.booking();
        String hotel = hotels.find(b.hotelId()).orElseThrow().name();
        outbox.queue(b.id(), change.to().name(), text.replace("{hotel}", hotel), "/pass/" + b.id());
    }

    /** The notification for this change, or null if it isn't one the traveller hears about. */
    static String text(BookingStatusChanged change, String hotel) {
        String deposit = "₹" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"))
                .format(change.booking().depositAmount());
        return switch (change.to()) {
            case HOTEL_CONFIRMED -> hotel + " confirmed. Your code is ready.";
            case HOTEL_DECLINED -> hotel + " couldn't take your booking. Your " + deposit
                    + " deposit is being refunded in full.";
            case HOTEL_TIMEOUT -> hotel + " didn't confirm in time. Your " + deposit
                    + " deposit is being refunded in full.";
            case CANCELLED -> change.from() == BookingStatus.DEPOSIT_PAID
                    ? "Your booking at " + hotel + " is cancelled. Your " + deposit + " deposit is being refunded."
                    : null;
            case HOTEL_FAILED -> "We're sorry " + hotel + " let you down. Your " + deposit
                    + " is being refunded, and your pass lists nearby hotels with rooms.";
            case REFUNDED -> "Your " + deposit + " refund has been processed.";
            default -> null;
        };
    }

    /** The 11:00 review request (ReviewInvites). */
    static String reviewText(String hotel) {
        return "How was " + hotel + "? Rate your stay in a few taps.";
    }
}
