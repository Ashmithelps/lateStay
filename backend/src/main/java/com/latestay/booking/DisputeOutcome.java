package com.latestay.booking;

/** How an admin settles a dispute (a Rescue is its own action). */
public enum DisputeOutcome {
    /** The guest stayed: COMPLETED, the deposit goes to the hotel. */
    GUEST_STAYED,
    /** The guest didn't arrive: NO_SHOW, final. */
    GUEST_DID_NOT_ARRIVE
}
