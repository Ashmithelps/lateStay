package com.latestay.booking;

/**
 * Published inside the transaction that moved the booking, so listeners (the WhatsApp outbox) write in the same
 * transaction and roll back with it. {@code from} is null when the booking is created.
 */
public record BookingStatusChanged(Booking booking, BookingStatus from, BookingStatus to, Actor actor) {
}
