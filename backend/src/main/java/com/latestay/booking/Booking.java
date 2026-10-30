package com.latestay.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A booking as stored. Money in rupees.
 *
 * @param phone null once deleted (90 days after the stay)
 * @param arrivalCheckSentAt when the owner was asked whether the guest arrived
 * @param completeBy when a confirmed booking nobody answered about becomes COMPLETED
 * @param disputeReason TRAVELLER_ARRIVED or TURNED_AWAY while (or after) DISPUTED
 * @param holdId the room hold this booking took over
 */
public record Booking(UUID id, UUID sessionId, UUID hotelId, UUID roomTypeId, LocalDate nightOf, String guestName,
        String phone, int guests, Instant arriveAt, Instant arriveBy, Instant leaveAt, long agreedPrice,
        long depositAmount, long payAtDesk, long commissionAmount, String code, BookingStatus status, Instant payBy,
        Instant confirmBy, Instant paidAt, Instant confirmedAt, Instant checkedInAt, Instant closedAt,
        Instant createdAt, Instant updatedAt, Instant arrivalCheckSentAt, Instant completeBy, String disputeReason,
        Instant disputedAt, Instant settledAt, UUID holdId) {

    public static final String TRAVELLER_ARRIVED = "TRAVELLER_ARRIVED";
    public static final String TURNED_AWAY = "TURNED_AWAY";

    /** This booking after moving to {@code to} at {@code now}, with that state's timestamps filled in. */
    Booking movedTo(BookingStatus to, Instant now, BookingProperties properties) {
        Instant paid = paidAt;
        Instant confirmDeadline = confirmBy;
        Instant confirmed = confirmedAt;
        Instant checkedIn = checkedInAt;
        Instant closed = closedAt;
        Instant disputed = disputedAt;
        Instant settled = settledAt;
        if (status == BookingStatus.DISPUTED) {
            settled = now;
        }
        switch (to) {
            case DEPOSIT_PAID -> {
                paid = now;
                confirmDeadline = now.plus(properties.confirmWindow());
            }
            case HOTEL_CONFIRMED -> confirmed = now;
            case CHECKED_IN -> checkedIn = now;
            case DISPUTED -> disputed = now;
            default -> {
            }
        }
        if (!to.isLive() && closed == null) {
            closed = now;
        }
        return new Booking(id, sessionId, hotelId, roomTypeId, nightOf, guestName, phone, guests, arriveAt, arriveBy,
                leaveAt, agreedPrice, depositAmount, payAtDesk, commissionAmount, code, to, payBy, confirmDeadline,
                paid, confirmed, checkedIn, closed, createdAt, now, arrivalCheckSentAt, completeBy, disputeReason,
                disputed, settled, holdId);
    }

    Booking withGuest(String name, String newPhone, Instant now) {
        return new Booking(id, sessionId, hotelId, roomTypeId, nightOf, name, newPhone, guests, arriveAt, arriveBy,
                leaveAt, agreedPrice, depositAmount, payAtDesk, commissionAmount, code, status, payBy, confirmBy,
                paidAt, confirmedAt, checkedInAt, closedAt, createdAt, now, arrivalCheckSentAt, completeBy,
                disputeReason, disputedAt, settledAt, holdId);
    }

    Booking withArrivalCheck(Instant sentAt, Instant newCompleteBy) {
        return new Booking(id, sessionId, hotelId, roomTypeId, nightOf, guestName, phone, guests, arriveAt, arriveBy,
                leaveAt, agreedPrice, depositAmount, payAtDesk, commissionAmount, code, status, payBy, confirmBy,
                paidAt, confirmedAt, checkedInAt, closedAt, createdAt, sentAt, sentAt, newCompleteBy, disputeReason,
                disputedAt, settledAt, holdId);
    }

    Booking withDisputeReason(String reason) {
        return new Booking(id, sessionId, hotelId, roomTypeId, nightOf, guestName, phone, guests, arriveAt, arriveBy,
                leaveAt, agreedPrice, depositAmount, payAtDesk, commissionAmount, code, status, payBy, confirmBy,
                paidAt, confirmedAt, checkedInAt, closedAt, createdAt, updatedAt, arrivalCheckSentAt, completeBy,
                reason, disputedAt, settledAt, holdId);
    }
}
