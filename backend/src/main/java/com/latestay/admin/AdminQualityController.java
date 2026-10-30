package com.latestay.admin;

import com.latestay.hotel.HotelRepository;
import com.latestay.pricing.SeasonPeriod;
import com.latestay.pricing.Seasons;
import com.latestay.reliability.HotelReliability;
import com.latestay.reliability.Reliability;
import com.latestay.review.ReviewDtos.AdminReview;
import com.latestay.review.Reviews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reviews (hide abusive text), hotel reliability (and "Reviewed, show again"), and the season calendar. */
@RestController
@RequestMapping("/api/admin")
class AdminQualityController {

    private final Reviews reviews;
    private final Reliability reliability;
    private final Seasons seasons;
    private final HotelRepository hotels;

    AdminQualityController(Reviews reviews, Reliability reliability, Seasons seasons, HotelRepository hotels) {
        this.reviews = reviews;
        this.reliability = reliability;
        this.seasons = seasons;
        this.hotels = hotels;
    }

    @GetMapping("/reviews")
    List<AdminReview> reviews() {
        return reviews.latest(AdminQueries.LIMIT);
    }

    @PostMapping("/reviews/{id}/hide-text")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hide(@PathVariable UUID id) {
        reviews.hideText(id, true);
    }

    @PostMapping("/reviews/{id}/show-text")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void show(@PathVariable UUID id) {
        reviews.hideText(id, false);
    }

    record ReliabilityRow(UUID hotelId, String name, String town, boolean active, HotelReliability reliability) {
    }

    /** Every hotel's last 30 days, paused ones first, then lowest score. */
    @GetMapping("/reliability")
    List<ReliabilityRow> reliability() {
        var all = hotels.findAll();
        Map<UUID, HotelReliability> scores = reliability.forHotels(all.stream().map(h -> h.id()).toList());
        return all.stream()
                .map(h -> new ReliabilityRow(h.id(), h.name(), h.town(), h.active(), scores.get(h.id())))
                .sorted(java.util.Comparator.comparing((ReliabilityRow r) -> r.reliability().pausedAt() == null)
                        .thenComparingInt(r -> r.reliability().score()))
                .toList();
    }

    /** "Reviewed, show again": back in search; only incidents after now can pause it again. */
    @PostMapping("/hotels/{id}/reliability-reviewed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reviewed(@PathVariable UUID id) {
        reliability.reviewed(id);
    }

    record SeasonBody(@NotBlank @Size(max = 80) String town, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn,
            @NotNull @Min(50) @Max(200) Integer multiplierPercent, @NotBlank @Size(max = 60) String label) {
    }

    @GetMapping("/seasons")
    List<SeasonPeriod> seasons() {
        return seasons.list();
    }

    @PostMapping("/seasons")
    @ResponseStatus(HttpStatus.CREATED)
    SeasonPeriod createSeason(@Valid @RequestBody SeasonBody body) {
        return seasons.create(body.town(), body.startsOn(), body.endsOn(), body.multiplierPercent(), body.label());
    }

    @DeleteMapping("/seasons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSeason(@PathVariable UUID id) {
        seasons.delete(id);
    }
}
