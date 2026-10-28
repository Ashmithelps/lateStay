package com.latestay.booking;

/** Where a booking is. Allowed moves live in {@link BookingTransitions}. */
public enum BookingStatus {
    PENDING_PAYMENT,
    DEPOSIT_PAID,
    HOTEL_CONFIRMED,
    CHECKED_IN,
    COMPLETED,
    EXPIRED,
    HOTEL_DECLINED,
    HOTEL_TIMEOUT,
    REFUNDED,
    CANCELLED,
    NO_SHOW;

    /** Holding a room and a desk code. */
    public boolean isLive() {
        return this == PENDING_PAYMENT || this == DEPOSIT_PAID || this == HOTEL_CONFIRMED || this == CHECKED_IN;
    }

    /** Waiting for a refund of the deposit. */
    public boolean awaitsRefund() {
        return this == HOTEL_DECLINED || this == HOTEL_TIMEOUT || this == CANCELLED;
    }
}
