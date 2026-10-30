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

    /** A tap on an arrival_check button. */
    record ArrivalAnswer(UUID bookingId, boolean arrived) implements OwnerReply {
    }

    /**
     * The owner typed a 4-digit number. It is a desk code (the guest is at the desk) only if it matches an active
     * booking at one of the owner's hotels; otherwise the owner gets the help reply.
     */
    record DeskCode(String code) implements OwnerReply {
    }

    /** "hisaab", "account" or "earnings": send a sign-in link to the owner page. */
    record AccountLink() implements OwnerReply {
    }

    record Unknown() implements OwnerReply {
    }
}
