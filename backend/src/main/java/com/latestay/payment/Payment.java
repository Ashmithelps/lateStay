package com.latestay.payment;

import java.util.UUID;

/** One Razorpay order for a booking's deposit. Amount in rupees. */
public record Payment(long id, UUID bookingId, String orderId, String paymentId, long amount, PaymentStatus status,
        String refundId, int refundAttempts, String lastError) {
}
