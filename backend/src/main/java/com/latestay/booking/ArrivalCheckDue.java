package com.latestay.booking;

/** Ask the owner whether the guest arrived (WhatsApp arrival_check). Published inside the booking's transaction. */
public record ArrivalCheckDue(Booking booking) {
}
