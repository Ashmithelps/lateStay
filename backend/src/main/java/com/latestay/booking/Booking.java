package com.latestay.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A booking as stored. Money in rupees. */
public record Booking(UUID id, UUID sessionId, UUID hotelId, UUID roomTypeId, LocalDate nightOf, String guestName,
        String phone, int guests, Instant arriveAt, Instant arriveBy, Instant leaveAt, long agreedPrice,
        long depositAmount, long payAtDesk, long commissionAmount, String code, BookingStatus status, Instant payBy,
        Instant confirmBy, Instant paidAt, Instant confirmedAt, Instant checkedInAt, Instant closedAt,
        Instant createdAt, Instant updatedAt) {

    /** This booking after moving to {@code to} at {@code now}, with that state's timestamps filled in. */
    Booking movedTo(BookingStatus to, Instant now, BookingProperties properties) {
        Instant paid = paidAt;
        Instant confirmDeadline = confirmBy;
        Instant confirmed = confirmedAt;
        Instant checkedIn = checkedInAt;
        Instant closed = closedAt;
        switch (to) {
            case DEPOSIT_PAID -> {
                paid = now;
                confirmDeadline = now.plus(properties.confirmWindow());
            }
            case HOTEL_CONFIRMED -> confirmed = now;
            case CHECKED_IN -> checkedIn = now;
            default -> {
                if (!to.isLive() && closed == null) {
                    closed = now;
                }
            }
        }
        return new Booking(id, sessionId, hotelId, roomTypeId, nightOf, guestName, phone, guests, arriveAt, arriveBy,
                leaveAt, agreedPrice, depositAmount, payAtDesk, commissionAmount, code, to, payBy, confirmDeadline,
                paid, confirmed, checkedIn, closed, createdAt, now);
    }

    Booking withGuest(String name, String newPhone, Instant now) {
        return new Booking(id, sessionId, hotelId, roomTypeId, nightOf, name, newPhone, guests, arriveAt, arriveBy,
                leaveAt, agreedPrice, depositAmount, payAtDesk, commissionAmount, code, status, payBy, confirmBy,
                paidAt, confirmedAt, checkedInAt, closedAt, createdAt, now);
    }
}
