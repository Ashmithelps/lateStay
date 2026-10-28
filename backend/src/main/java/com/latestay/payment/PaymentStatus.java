package com.latestay.payment;

/**
 * CREATED: order made, not paid. CAPTURED: deposit received. REFUND_PENDING: the refund job still has to call
 * Razorpay. REFUND_REQUESTED: called, waiting for refund.processed. REFUNDED: money back with the traveller.
 */
public enum PaymentStatus {
    CREATED, CAPTURED, REFUND_PENDING, REFUND_REQUESTED, REFUNDED
}
