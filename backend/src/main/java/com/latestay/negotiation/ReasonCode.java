package com.latestay.negotiation;

public enum ReasonCode {
    OPENING,
    LATE_HOUR,
    COUNTER_TOO_LOW,
    MEETING_HALFWAY,
    CLOSE_ENOUGH,
    FLOOR_REACHED,
    ALREADY_FINAL,
    ACCEPTED,
    /** The traveller's turn was not a price move; the offer stands. */
    UNCLEAR
}
