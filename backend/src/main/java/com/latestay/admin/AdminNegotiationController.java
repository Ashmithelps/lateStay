package com.latestay.admin;

import com.latestay.admin.AdminViews.AdminNegotiation;
import com.latestay.admin.AdminViews.AdminTranscript;
import com.latestay.admin.AdminViews.AvailabilityRow;
import com.latestay.admin.AdminViews.Tonight;
import com.latestay.hotel.NotFoundException;
import com.latestay.stay.LateStayTime;
import com.latestay.stay.StayWindow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Negotiation transcripts and tonight's availability for the admin panel. */
@RestController
@RequestMapping("/api/admin")
class AdminNegotiationController {

    private final AdminQueries queries;
    private final Clock clock;

    AdminNegotiationController(AdminQueries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    /** @param state {@code live} (still haggling) or {@code recent} (opened in the last 24 hours, any status) */
    @GetMapping("/negotiations")
    List<AdminNegotiation> negotiations(@RequestParam(defaultValue = "live") String state) {
        return queries.negotiations("live".equals(state));
    }

    @GetMapping("/negotiations/{id}")
    AdminTranscript negotiation(@PathVariable UUID id) {
        AdminNegotiation negotiation = queries.negotiation(id)
                .orElseThrow(() -> new NotFoundException("negotiation", id));
        return new AdminTranscript(negotiation, queries.turns(id));
    }

    /** Rooms free per hotel for a night; defaults to tonight (an hour before 06:00 still counts as last night). */
    @GetMapping("/availability")
    Tonight availability(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate nightOf) {
        LocalDate night = nightOf != null ? nightOf : StayWindow.nightOf(LocalDateTime.ofInstant(clock.instant(), LateStayTime.ZONE));
        List<AvailabilityRow> rows = queries.availability(night);
        return new Tonight(night, rows);
    }
}
