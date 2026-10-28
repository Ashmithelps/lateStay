package com.latestay.booking;

/** A booking was asked to move somewhere its state machine doesn't allow. Maps to 409. */
public class IllegalTransitionException extends RuntimeException {

    private final BookingStatus from;
    private final BookingStatus to;

    public IllegalTransitionException(BookingStatus from, BookingStatus to) {
        super("booking can't go from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public BookingStatus from() {
        return from;
    }

    public BookingStatus to() {
        return to;
    }
}
