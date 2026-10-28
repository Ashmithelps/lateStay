package com.latestay.session;

import io.swagger.v3.oas.annotations.media.Schema;
import com.latestay.negotiation.SessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A negotiation as the traveller sees it, for reloading the chat or the booking page. Never includes the floor.
 * {@code status} reads EXPIRED once the session's time is up, even before the next message records it.
 */
public record NegotiationView(UUID id, SessionStatus status, long currentOffer, @Schema(nullable = true) Long agreedPrice, int round,
        int maxRounds, Instant expiresAt, UUID stayRequestId, HotelSummary hotel, RoomSummary roomType,
        List<Message> messages) {

    public record HotelSummary(UUID id, String slug, String name, String town, @Schema(nullable = true) String photo) {
    }

    public record RoomSummary(UUID id, String name) {
    }

    /** @param role TRAVELLER or BOT */
    public record Message(String role, String text) {
    }
}
