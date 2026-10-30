package com.latestay.booking;

/** Something an admin must look at now, e.g. a traveller turned away at the desk. Sent to ADMIN_WHATSAPP_NUMBER. */
public record AdminAlert(String what, Booking booking) {
}
