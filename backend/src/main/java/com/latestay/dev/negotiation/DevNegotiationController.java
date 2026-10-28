package com.latestay.dev.negotiation;

import com.latestay.conversation.ConversationStore;
import com.latestay.negotiation.EngineDecision;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.PricingPolicy;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Dev-only endpoints for trying the negotiation engine by hand. Active in the 'local' profile. */
@RestController
@Profile("local")
@RequestMapping("/api/dev/negotiations")
class DevNegotiationController {

    private final NegotiationEngine engine;
    private final ConversationStore store;

    DevNegotiationController(NegotiationEngine engine, ConversationStore store) {
        this.engine = engine;
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse create(@Valid @RequestBody CreateNegotiationRequest request) {
        PricingPolicy policy = PricingPolicy.of(request.listPrice(), request.floorPrice(), request.slotType());
        NegotiationSession session = engine.open(policy, request.arrival());
        store.save(session);
        return SessionResponse.from(session);
    }

    @PostMapping("/{id}/turns")
    TurnResponse turn(@PathVariable String id, @Valid @RequestBody TurnRequest request) {
        NegotiationSession session = store.find(id)
                .map(ConversationStore.Conversation::session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No negotiation " + id));
        EngineDecision decision = engine.respond(session, request.toEvent());
        return new TurnResponse(decision, session.status());
    }
}
