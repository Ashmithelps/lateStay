package com.latestay.session;

import com.latestay.negotiation.SessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A new negotiation. Never includes the floor price.
 *
 * @param roomType the (first) room type; its name is "Deluxe + Standard" for two rooms
 * @param rooms 1, or 2 for two rooms negotiated together
 */
public record NegotiationCreated(UUID id, SessionStatus status, long currentOffer, int round, int maxRounds,
        Instant expiresAt, Ref hotel, Ref roomType, int rooms, List<Message> messages) {

    public record Ref(UUID id, String name) {
    }

    public record Message(String role, String text) {
    }
}
