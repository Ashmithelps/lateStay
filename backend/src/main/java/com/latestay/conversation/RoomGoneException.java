package com.latestay.conversation;

/** The last room went to someone else just before this traveller accepted. Maps to 409 ROOM_GONE. */
public class RoomGoneException extends RuntimeException {

    public RoomGoneException(String hotelName) {
        super("Sorry, the last room at " + hotelName + " just went.");
    }
}
