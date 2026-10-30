package com.latestay.dev.owner;

import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.owner.OwnerSessions;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local profile only: a fresh owner sign-in link for a hotel by slug, as "hisaab" on WhatsApp would send. The
 * WhatsApp log shows sent links redacted, so development and the screenshot run use this instead.
 */
@Profile("local")
@RestController
class DevOwnerLinks {

    private final OwnerSessions sessions;
    private final HotelRepository hotels;
    private final String webUrl;

    DevOwnerLinks(OwnerSessions sessions, HotelRepository hotels, @Value("${latestay.public-web-url}") String webUrl) {
        this.sessions = sessions;
        this.hotels = hotels;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    @PostMapping("/api/dev/owner-links")
    Map<String, String> link(@RequestParam String hotel) {
        var found = hotels.findBySlug(hotel).orElseThrow(() -> new NotFoundException("hotel", hotel));
        return Map.of("url", webUrl + "/owner/login?t=" + sessions.newLinkToken(found.id()));
    }
}
