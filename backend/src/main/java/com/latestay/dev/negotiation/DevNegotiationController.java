package com.latestay.dev.negotiation;

import com.latestay.conversation.UnknownConversationException;
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

/**
 * Dev-only endpoints for trying the negotiation engine with arbitrary prices, used by the simulator. Sessions are
 * stored like real ones. Active in the 'local' profile.
 */
@RestController
@Profile("local")
@RequestMapping("/api/dev/negotiations")
class DevNegotiationController {

    private final DevNegotiationService devSessions;

    DevNegotiationController(DevNegotiationService devSessions) {
        this.devSessions = devSessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse create(@Valid @RequestBody CreateNegotiationRequest request) {
        PricingPolicy policy = PricingPolicy.of(request.listPrice(), request.floorPrice(), request.slotType());
        return SessionResponse.from(devSessions.create(policy, request.arrival()));
    }

    @PostMapping("/{id}/turns")
    TurnResponse turn(@PathVariable String id, @Valid @RequestBody TurnRequest request) {
        try {
            return devSessions.turn(id, request.toEvent());
        } catch (UnknownConversationException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
