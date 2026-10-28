package com.latestay.booking;

/** A booking request or payment confirmation that can't be accepted as sent. Maps to 400. */
public class InvalidBookingException extends RuntimeException {

    public InvalidBookingException(String message) {
        super(message);
    }
}
