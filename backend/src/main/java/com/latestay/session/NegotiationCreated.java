package com.latestay.session;

import com.latestay.negotiation.SessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A new negotiation. Never includes the floor price. */
public record NegotiationCreated(UUID id, SessionStatus status, long currentOffer, int round, int maxRounds,
        Instant expiresAt, Ref hotel, Ref roomType, List<Message> messages) {

    public record Ref(UUID id, String name) {
    }

    public record Message(String role, String text) {
    }
}
