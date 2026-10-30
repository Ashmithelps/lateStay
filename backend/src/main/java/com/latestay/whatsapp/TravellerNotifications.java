package com.latestay.whatsapp;

import com.latestay.booking.Booking;
import com.latestay.booking.BookingRepository;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.BookingStatusChanged;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.stay.LateStayTime;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells travellers who ticked "Send my booking pass and updates on WhatsApp" about their booking:
 * booking_confirmed when the hotel confirms, and one booking_update when it is declined, times out, is cancelled
 * after payment, is rescued, or its refund goes through. Nothing for anyone who didn't tick it, or once the phone
 * number has been deleted. Runs inside the booking's transaction (queue only).
 */
@Component
class TravellerNotifications {

    private final WhatsAppOutbox outbox;
    private final HotelRepository hotels;
    private final BookingRepository bookings;
    private final WhatsAppProperties properties;
    private final String webUrl;

    TravellerNotifications(WhatsAppOutbox outbox, HotelRepository hotels, BookingRepository bookings,
            WhatsAppProperties properties, @Value("${latestay.public-web-url}") String webUrl) {
        this.outbox = outbox;
        this.hotels = hotels;
        this.bookings = bookings;
        this.properties = properties;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    @EventListener
    void on(BookingStatusChanged change) {
        Booking b = change.booking();
        String line = change.to() == BookingStatus.HOTEL_CONFIRMED ? "" : updateLine(change);
        if (line == null || b.phone() == null || !bookings.wantsWhatsAppUpdates(b.id())) {
            return;
        }
        Hotel hotel = hotels.find(b.hotelId()).orElseThrow();
        String to = WhatsAppOutbox.waId(b.phone());
        String pass = webUrl + "/pass/" + b.id();
        if (change.to() == BookingStatus.HOTEL_CONFIRMED) {
            String support = properties.supportLink() != null ? properties.supportLink() : webUrl + "/contact";
            outbox.queue(null, WhatsAppMessages.bookingConfirmed(to, properties.language(), hotel.name(),
                            hotel.address(), LocalDateTime.ofInstant(b.arriveBy(), LateStayTime.ZONE).toLocalTime(),
                            b.payAtDesk(), b.code(), pass, support),
                    b.id(), WhatsAppMessages.BOOKING_CONFIRMED + ":" + b.id(), WhatsAppOutbox.Audience.TRAVELLER);
        } else {
            outbox.queue(null, WhatsAppMessages.bookingUpdate(to, properties.language(),
                            line.replace("{hotel}", hotel.name()), pass),
                    b.id(), WhatsAppMessages.BOOKING_UPDATE + ":" + b.id() + ":" + change.to(),
                    WhatsAppOutbox.Audience.TRAVELLER);
        }
    }

    /** The booking_update line for this change, or null if it isn't one the traveller hears about. */
    static String updateLine(BookingStatusChanged change) {
        String deposit = "₹" + WhatsAppMessages.rupees(change.booking().depositAmount());
        return switch (change.to()) {
            case HOTEL_DECLINED -> "{hotel} couldn't take your booking, so your " + deposit
                    + " deposit is being refunded in full";
            case HOTEL_TIMEOUT -> "{hotel} didn't confirm in time, so your " + deposit
                    + " deposit is being refunded in full";
            // Only after payment: an unpaid booking that is cancelled has nothing to tell.
            case CANCELLED -> change.from() == BookingStatus.DEPOSIT_PAID
                    ? "your booking at {hotel} is cancelled and your " + deposit + " deposit is being refunded"
                    : null;
            case HOTEL_FAILED -> "we're sorry {hotel} let you down; your " + deposit + " is being refunded and "
                    + "your pass lists nearby hotels with rooms";
            case REFUNDED -> "your " + deposit + " refund has been processed";
            default -> null;
        };
    }
}
