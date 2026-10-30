package com.latestay.share;

/** A share link that has expired or was turned off (410). */
public class StayShareGoneException extends RuntimeException {

    public enum Reason { EXPIRED, TURNED_OFF }

    private final Reason reason;

    public StayShareGoneException(Reason reason) {
        super(reason == Reason.EXPIRED ? "This link has expired." : "This link was turned off.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
