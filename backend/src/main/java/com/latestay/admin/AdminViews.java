package com.latestay.admin;

import com.latestay.booking.BookingEvent;
import com.latestay.booking.BookingStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Admin read models. Unlike the public API these include floor prices and desk codes. Times local where named. */
final class AdminViews {

    private AdminViews() {
    }

    record AdminBooking(UUID id, BookingStatus status, UUID hotelId, String hotelName, String roomType,
            String guestName, String phone, int guests, LocalDateTime arriveAt, LocalDateTime arriveBy,
            LocalDateTime leaveAt, long agreedPrice, long depositAmount, long payAtDesk, long commissionAmount,
            String code, Instant payBy, Instant confirmBy, String paymentStatus, String razorpayPaymentId,
            String refundId, Instant createdAt, Instant updatedAt, UUID sessionId, String disputeReason,
            Instant disputedAt, Instant arrivalCheckSentAt, int hotelFailures, Instant hideUntil,
            Instant keepHiddenAt) {
    }

    record AdminBookingDetail(AdminBooking booking, List<BookingEvent> events) {
    }

    record AdminNegotiation(UUID id, String status, String hotelName, String roomType, long listPrice,
            long floorPrice, long currentOffer, Long agreedPrice, int round, int maxRounds, int turns,
            LocalDateTime arriveAt, int guests, Instant expiresAt, Instant updatedAt, UUID bookingId) {
    }

    /** One stored turn: the words, the parsed intent and the engine decision as stored (JSON). */
    record AdminTurn(int seq, String role, String text, String intent, String decision, String validatorResult,
            Integer latencyMs, Instant at) {
    }

    record AdminTranscript(AdminNegotiation negotiation, List<AdminTurn> turns) {
    }

    /** {@code roomsFree} is the owner's report; {@code available} subtracts holds and bookings made since. */
    record AvailabilityRow(UUID hotelId, String hotelName, String town, boolean active, Integer roomsFree,
            Instant reportedAt, String source, int available, int reliabilityFailures) {
    }

    record Tonight(LocalDate nightOf, List<AvailabilityRow> hotels) {
    }
}
