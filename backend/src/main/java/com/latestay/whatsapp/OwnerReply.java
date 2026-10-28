package com.latestay.whatsapp;

import java.time.LocalDate;
import java.util.UUID;

/** What an owner's WhatsApp message means. */
public sealed interface OwnerReply {

    /**
     * Rooms free for late arrivals.
     *
     * @param hotelId the hotel, when the reply came from a ping's button; null for free text
     * @param nightOf the night the ping asked about; null for free text (meaning tonight)
     */
    record RoomsFree(int rooms, UUID hotelId, LocalDate nightOf) implements OwnerReply {
    }

    enum Action { CONFIRM, DECLINE }

    /** A tap on a booking_request button. */
    record BookingAnswer(UUID bookingId, Action action) implements OwnerReply {
    }

    record Unknown() implements OwnerReply {
    }
}
