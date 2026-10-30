package com.latestay.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The web app's funnel events. Only the browser-side events are accepted here; rate-limited per IP. */
@RestController
class AnalyticsController {

    /**
     * @param name one of the browser events, e.g. "room_now_tapped"
     * @param distinctId the browser's random analytics id (never a phone number or name)
     */
    record EventBody(@NotBlank @Size(max = 40) String name,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String distinctId,
            @Schema(nullable = true) UUID stayRequestId,
            @Schema(nullable = true, description = "For chat and booking steps: the stay is found from it")
            UUID negotiationId,
            @Schema(nullable = true) @Size(max = 100) String utmSource,
            @Schema(nullable = true) Map<String, Object> properties) {
    }

    private final Analytics analytics;

    AnalyticsController(Analytics analytics) {
        this.analytics = analytics;
    }

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void event(@RequestBody @Valid EventBody body) {
        AnalyticsEventName event = AnalyticsEventName.fromWire(body.name()).filter(AnalyticsEventName::fromWeb)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown event"));
        UUID stay = body.stayRequestId() != null ? body.stayRequestId()
                : body.negotiationId() == null ? null : analytics.stayOfNegotiation(body.negotiationId());
        analytics.record(event, body.distinctId(), stay, body.utmSource(), body.properties());
    }
}
