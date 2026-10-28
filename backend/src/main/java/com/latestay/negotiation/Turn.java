package com.latestay.negotiation;

import java.time.Instant;
import java.util.Optional;

/**
 * One entry in a session's history. The opening offer is round 0 and has no traveller event.
 */
public record Turn(int round, Optional<TravellerEvent> event, EngineDecision decision, Instant at) {
}
