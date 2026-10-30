package com.latestay.stay;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    /**
     * GET /api/search?lat=32.24&lng=77.19&arrive=2026-10-28T23:30&leave=2026-10-29T09:00&guests=2&filters=heater,parking
     * Times are local (Asia/Kolkata). Records a stay request; its id comes back as {@code stayRequestId}.
     */
    @GetMapping("/api/search")
    SearchResponse search(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime arrive,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime leave,
            @RequestParam(defaultValue = "1") int guests,
            @RequestParam(required = false) String filters,
            @RequestParam(name = "utm_source", required = false) String utmSource,
            @RequestParam(name = "utm_medium", required = false) String utmMedium,
            @RequestParam(name = "utm_campaign", required = false) String utmCampaign,
            @RequestParam(name = "aid", required = false) String analyticsId) {
        List<SearchFilter> parsed = filters == null || filters.isBlank() ? List.of()
                : Arrays.stream(filters.split(",")).filter(f -> !f.isBlank()).map(SearchFilter::parse).distinct().toList();
        return search.search(lat, lng, StayWindow.of(arrive, leave), guests, parsed,
                Attribution.of(utmSource, utmMedium, utmCampaign, analyticsId));
    }
}
