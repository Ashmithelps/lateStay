package com.latestay.dev.negotiation;

import com.latestay.negotiation.EngineDecision;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.SessionStatus;
import com.latestay.negotiation.SlotType;
import com.latestay.negotiation.TravellerEvent;
import com.latestay.negotiation.Turn;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** Session view for the dev API. Deliberately omits the floor price. */
record SessionResponse(
        String id,
        SessionStatus status,
        long listPrice,
        SlotType slotType,
        LocalTime arrival,
        long currentOffer,
        int round,
        int maxRounds,
        Long agreedPrice,
        Instant expiresAt,
        List<TurnView> history) {

    record TurnView(int round, TurnType type, Long amount, EngineDecision decision, Instant at) {

        static TurnView from(Turn turn) {
            TurnType type = turn.event().map(TurnType::of).orElse(null);
            Long amount = turn.event()
                    .filter(TravellerEvent.Counter.class::isInstance)
                    .map(e -> ((TravellerEvent.Counter) e).amount())
                    .orElse(null);
            return new TurnView(turn.round(), type, amount, turn.decision(), turn.at());
        }
    }

    static SessionResponse from(NegotiationSession session) {
        return new SessionResponse(
                session.id(),
                session.status(),
                session.policy().listPrice(),
                session.policy().slotType(),
                session.arrival(),
                session.currentOffer(),
                session.round(),
                session.policy().maxRounds(),
                session.agreedPrice().orElse(null),
                session.expiresAt(),
                session.history().stream().map(TurnView::from).toList());
    }
}
