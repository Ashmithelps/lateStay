package com.latestay.booking;

import com.latestay.payment.RazorpayGateway;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Public request and response bodies for /api/bookings. Money in rupees unless named paise. */
public final class BookingDtos {

    private BookingDtos() {
    }

    /** @param idConsent the traveller ticked "I'll carry a photo ID for every guest" */
    public record CreateBookingRequest(@NotNull UUID sessionId, @NotBlank @Size(max = 80) String guestName,
            @NotBlank @Size(max = 20) String phone, @AssertTrue boolean idConsent) {
    }

    /** {@code checkout} is null once the booking is past payment (the web app goes straight to the pass). */
    public record BookingCreated(UUID bookingId, BookingStatus status, Instant payBy,
            @Schema(nullable = true) Checkout checkout) {
    }

    /**
     * What Razorpay Checkout needs. In FAKE mode (local, no keys) the web app completes the payment through the dev
     * API instead of opening Checkout.
     */
    public record Checkout(RazorpayGateway.Mode mode, String keyId, String orderId, long amount, long amountPaise,
            String currency, String name, String phone, long timeoutSeconds) {
    }

    /** Razorpay Checkout's success handler fields. */
    public record VerifyPaymentRequest(@NotBlank String orderId, @NotBlank String paymentId,
            @NotBlank String signature) {
    }

    /**
     * A booking as the traveller's pass shows it. Times are local (Asia/Kolkata). {@code code} is only present once
     * the hotel has confirmed; {@code nextBest} only when the booking fell through; {@code refundedFrom} says why a
     * REFUNDED booking was refunded (HOTEL_DECLINED, HOTEL_TIMEOUT or CANCELLED).
     */
    /** {@code phone} is null once it has been deleted, 90 days after the stay. */
    public record BookingView(UUID id, UUID sessionId, BookingStatus status, HotelInfo hotel, String roomType,
            String guestName, @Schema(nullable = true) String phone, int guests, LocalDateTime arriveAt, LocalDateTime arriveBy,
            LocalDateTime leaveAt, long agreedPrice, long depositAmount, long payAtDesk,
            @Schema(nullable = true) String code, Instant payBy, @Schema(nullable = true) Instant confirmBy,
            @Schema(nullable = true) Refund refund, @Schema(nullable = true) NextBest nextBest,
            @Schema(nullable = true) BookingStatus refundedFrom) {
    }

    /**
     * What the booking page shows before paying: the agreed price split into the deposit and the desk payment.
     * {@code booking} is the negotiation's live booking, if there already is one.
     */
    public record BookingPreview(UUID sessionId, HotelInfo hotel, String roomType, LocalDateTime arriveAt,
            LocalDateTime arriveBy, LocalDateTime leaveAt, int guests, long agreedPrice, long depositAmount,
            long payAtDesk, @Schema(nullable = true) ExistingBooking booking) {
    }

    public record ExistingBooking(UUID id, BookingStatus status) {
    }

    public record HotelInfo(UUID id, String slug, String name, String town, String address,
            @Schema(nullable = true) String photo, String phone, double lat, double lng) {
    }

    /** @param status PENDING until Razorpay reports the refund processed */
    public record Refund(long amount, String status) {
    }

    /** The closest other hotel with rooms for the original search, to try next. */
    public record NextBest(String slug, String name, String town, @Schema(nullable = true) String photo,
            double distanceKm, long fromPrice, UUID stayRequestId) {
    }
}
