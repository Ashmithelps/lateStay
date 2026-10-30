package com.latestay.conversation;

/**
 * Sets a room aside when a negotiation is about to be agreed. Called inside the agreeing transaction, before the
 * agreement is saved: if it throws, nothing is agreed.
 */
public interface RoomGuard {

    /** @throws RoomGoneException if the hotel has no room left for the stay's night */
    void reserve(String sessionId);
}
