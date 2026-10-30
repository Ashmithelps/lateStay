package com.latestay.session;

import com.latestay.hotel.NotFoundException;
import com.latestay.stay.InvalidStayException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    /**
     * @param roomTypeId one room
     * @param roomTypeIds or two rooms at the same hotel, negotiated together (a group no single room fits)
     */
    record CreateRequest(@NotNull UUID stayRequestId, @Schema(nullable = true) UUID roomTypeId,
            @Schema(nullable = true) @Size(min = 1, max = 2) List<@NotNull UUID> roomTypeIds) {

        List<UUID> rooms() {
            if (roomTypeIds != null && !roomTypeIds.isEmpty()) {
                return roomTypeIds;
            }
            if (roomTypeId == null) {
                throw new InvalidStayException("roomTypeId or roomTypeIds is required");
            }
            return List.of(roomTypeId);
        }
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
        return negotiations.create(request.stayRequestId(), request.rooms());
    }
}
