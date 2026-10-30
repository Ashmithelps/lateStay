package com.latestay.share;

import com.latestay.share.ShareDtos.ShareLink;
import com.latestay.share.ShareDtos.SharedStay;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StayShareController {

    private final StayShares shares;

    StayShareController(StayShares shares) {
        this.shares = shares;
    }

    /** The pass's live share link; 204 when there is none. */
    @GetMapping("/api/bookings/{id}/share")
    ResponseEntity<ShareLink> current(@PathVariable UUID id) {
        return shares.current(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/bookings/{id}/share")
    @ResponseStatus(HttpStatus.CREATED)
    ShareLink create(@PathVariable UUID id) {
        return shares.create(id);
    }

    @DeleteMapping("/api/bookings/{id}/share")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void turnOff(@PathVariable UUID id) {
        shares.turnOff(id);
    }

    /** The shared page's data; 410 once expired or turned off. Never cached. */
    @GetMapping("/api/stays/{token}")
    ResponseEntity<SharedStay> view(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Robots-Tag", "noindex")
                .body(shares.view(token));
    }
}
