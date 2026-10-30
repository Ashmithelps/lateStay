package com.latestay.town;

import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Data for the static /towns pages (rebuilt hourly by the web app). */
@RestController
class TownController {

    private final Towns towns;

    TownController(Towns towns) {
        this.towns = towns;
    }

    @GetMapping("/api/towns")
    ResponseEntity<List<Towns.TownSummary>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10))).body(towns.list());
    }

    @GetMapping("/api/towns/{slug}")
    ResponseEntity<Towns.TownPage> page(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10))).body(towns.page(slug));
    }
}
