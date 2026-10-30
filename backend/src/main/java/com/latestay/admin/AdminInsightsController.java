package com.latestay.admin;

import com.latestay.digest.Digest;
import com.latestay.digest.DigestService;
import com.latestay.digest.Funnel;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** /admin/today (the morning digest's numbers) and /admin/funnel. */
@RestController
@RequestMapping("/api/admin")
class AdminInsightsController {

    private final DigestService digests;
    private final Funnel funnel;

    AdminInsightsController(DigestService digests, Funnel funnel) {
        this.digests = digests;
        this.funnel = funnel;
    }

    /** @param night a night_of; default the night that ended this morning */
    @GetMapping("/today")
    Digest today(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate night) {
        return digests.forNight(night == null ? digests.lastNight() : night);
    }

    @GetMapping("/funnel")
    Funnel.Report funnel(@RequestParam(defaultValue = "7") int days, @RequestParam(required = false) String town,
            @RequestParam(required = false) String utmSource) {
        if (days != 7 && days != 30) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "days is 7 or 30");
        }
        return funnel.report(days, town, utmSource);
    }
}
