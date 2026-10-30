package com.latestay.whatsapp;

import com.latestay.booking.AdminAlert;
import com.latestay.booking.ArrivalCheckDue;
import com.latestay.booking.Booking;
import com.latestay.booking.BookingRepository;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.BookingStatusChanged;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.stay.LateStayTime;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the owner about bookings: booking_request once the deposit is paid, booking_cancelled when a paid booking
 * is cancelled or times out before they answered. Runs inside the booking's transaction (queue only).
 */
@Component
class BookingNotifications {

    private static final Logger log = LoggerFactory.getLogger(BookingNotifications.class);

    private final WhatsAppOutbox outbox;
    private final HotelRepository hotels;
    private final WhatsAppProperties properties;
    private final BookingRepository bookings;

    BookingNotifications(WhatsAppOutbox outbox, HotelRepository hotels, WhatsAppProperties properties,
            BookingRepository bookings) {
        this.bookings = bookings;
        this.outbox = outbox;
        this.hotels = hotels;
        this.properties = properties;
    }

    @EventListener
    void on(BookingStatusChanged change) {
        Booking b = change.booking();
        if (change.to() == BookingStatus.DEPOSIT_PAID) {
            Hotel hotel = hotels.find(b.hotelId()).orElseThrow();
            int rooms = bookings.rooms(b.id()).count();
            String to = WhatsAppOutbox.waId(hotel.whatsappNumber());
            LocalTime arrive = LocalDateTime.ofInstant(b.arriveAt(), LateStayTime.ZONE).toLocalTime();
            outbox.queue(hotel.id(), rooms > 1 && properties.roomsTemplate()
                            ? WhatsAppMessages.bookingRequestRooms(to, properties.language(), b.id(), b.guests(), rooms,
                                    arrive, b.payAtDesk(), b.depositAmount(), b.code())
                            : WhatsAppMessages.bookingRequest(to, properties.language(), b.id(), b.guests(), arrive,
                                    b.payAtDesk(), b.depositAmount(), b.code()),
                    b.id(), WhatsAppMessages.BOOKING_REQUEST + ":" + b.id());
        }
        boolean wasWaiting = change.from() == BookingStatus.DEPOSIT_PAID;
        if (wasWaiting && (change.to() == BookingStatus.CANCELLED || change.to() == BookingStatus.HOTEL_TIMEOUT)) {
            Hotel hotel = hotels.find(b.hotelId()).orElseThrow();
            String reason = (change.to() == BookingStatus.CANCELLED
                    ? "the guest cancelled"
                    : "it wasn't confirmed within 5 minutes") + roomsNote(b);
            outbox.queue(hotel.id(), WhatsAppMessages.bookingCancelled(WhatsAppOutbox.waId(hotel.whatsappNumber()),
                    properties.language(), b.code(), reason), b.id(), WhatsAppMessages.BOOKING_CANCELLED + ":" + b.id());
        }
    }

    @EventListener
    void on(ArrivalCheckDue due) {
        Booking b = due.booking();
        Hotel hotel = hotels.find(b.hotelId()).orElseThrow();
        outbox.queue(hotel.id(), WhatsAppMessages.arrivalCheck(WhatsAppOutbox.waId(hotel.whatsappNumber()),
                properties.language(), b.id(), b.guestName() + roomsNote(b), b.code()), b.id(),
                WhatsAppMessages.ARRIVAL_CHECK + ":" + b.id());
    }

    /** ", 2 rooms" for a group's booking (fits the arrival_check and booking_cancelled variables), else "". */
    private String roomsNote(Booking b) {
        int rooms = bookings.rooms(b.id()).count();
        return rooms > 1 ? ", " + rooms + " rooms" : "";
    }

    /** To the admin's own WhatsApp (ADMIN_WHATSAPP_NUMBER), as a template: they may be outside the 24-hour window. */
    @EventListener
    void on(AdminAlert alert) {
        if (properties.adminNumber().isBlank()) {
            log.warn("ADMIN_WHATSAPP_NUMBER not set: alert for booking {} ({}) not sent", alert.booking().id(),
                    alert.what());
            return;
        }
        Booking b = alert.booking();
        Hotel hotel = hotels.find(b.hotelId()).orElseThrow();
        outbox.queue(null, WhatsAppMessages.adminAlert(WhatsAppOutbox.waId(properties.adminNumber()),
                properties.language(), alert.what(), b.code(), hotel.name()), b.id(),
                WhatsAppMessages.ADMIN_ALERT + ":" + b.id() + ":" + b.disputeReason(), WhatsAppOutbox.Audience.ADMIN);
    }
}
