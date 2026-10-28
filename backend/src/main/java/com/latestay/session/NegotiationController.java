package com.latestay.session;

import com.latestay.hotel.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NegotiationController {

    record CreateRequest(@NotNull UUID stayRequestId, @NotNull UUID roomTypeId) {
    }

    private final NegotiationService negotiations;
    private final JdbcConversationRepository sessions;

    NegotiationController(NegotiationService negotiations, JdbcConversationRepository sessions) {
        this.negotiations = negotiations;
        this.sessions = sessions;
    }

    @GetMapping("/api/negotiations/{id}")
    NegotiationView get(@PathVariable UUID id) {
        return sessions.view(id).orElseThrow(() -> new NotFoundException("negotiation", id));
    }

    @PostMapping("/api/negotiations")
    @ResponseStatus(HttpStatus.CREATED)
    NegotiationCreated create(@Valid @RequestBody CreateRequest request) {
        return negotiations.create(request.stayRequestId(), request.roomTypeId());
    }
}
