package com.latestay.stay;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The home page's first results are fetched by the server, which can't see the browser's first-touch UTM tags or
 * analytics id; the browser adds them here once. Rate-limited with the events.
 */
@RestController
class AttributionController {

    record AttributionBody(@Schema(nullable = true) String utmSource, @Schema(nullable = true) String utmMedium,
            @Schema(nullable = true) String utmCampaign, @Schema(nullable = true) String analyticsId) {
    }

    private final StayRequestRepository stayRequests;

    AttributionController(StayRequestRepository stayRequests) {
        this.stayRequests = stayRequests;
    }

    @PostMapping("/api/stay-requests/{id}/attribution")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void attribute(@PathVariable UUID id, @RequestBody AttributionBody body) {
        stayRequests.attributeIfUntagged(id, Attribution.of(body.utmSource(), body.utmMedium(), body.utmCampaign(),
                body.analyticsId()));
    }
}
