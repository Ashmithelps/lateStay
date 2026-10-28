package com.latestay.negotiation;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Everything needed to rebuild a {@link NegotiationSession}, e.g. after loading it from storage. The engine's
 * decisions depend only on this state, never on the turn history.
 */
public record SessionSnapshot(String id, PricingPolicy policy, LocalTime arrival, Instant createdAt, Instant expiresAt,
        long currentOffer, int round, SessionStatus status, Long agreedPrice) {

    public SessionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        if ((status == SessionStatus.AGREED) != (agreedPrice != null)) {
            throw new IllegalArgumentException("agreedPrice must be set exactly when the session is AGREED");
        }
        if (currentOffer < policy.effectiveFloor() || (agreedPrice != null && agreedPrice < policy.effectiveFloor())) {
            throw new IllegalArgumentException("snapshot price below floor");
        }
        if (round < 0) {
            throw new IllegalArgumentException("round must not be negative");
        }
    }
}
