package com.latestay.phone;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * "This number was verified on this device": an httpOnly, SameSite=Lax cookie holding the phone's hash and an
 * expiry, signed with HMAC. No phone number in it. The web app and API share a site (latestay.in and
 * api.latestay.in), so the browser sends it with the booking request.
 */
@Component
public class PhoneCookies {

    public static final String NAME = "ls_phone";

    private final PhoneHashes hashes;
    private final PhoneVerificationProperties properties;
    private final Clock clock;

    public PhoneCookies(PhoneHashes hashes, PhoneVerificationProperties properties, Clock clock) {
        this.hashes = hashes;
        this.properties = properties;
        this.clock = clock;
    }

    public ResponseCookie issue(String phoneHash) {
        long expires = clock.instant().plus(properties.remember()).getEpochSecond();
        String payload = phoneHash + "." + expires;
        return ResponseCookie.from(NAME, payload + "." + hashes.sign(payload))
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/api")
                .maxAge(properties.remember())
                .build();
    }

    public ResponseCookie clear() {
        return ResponseCookie.from(NAME, "").httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path("/api").maxAge(Duration.ZERO).build();
    }

    /** The verified phone's hash, if the request carries a valid, unexpired cookie. */
    public Optional<String> verifiedPhoneHash(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (NAME.equals(cookie.getName())) {
                return check(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    Optional<String> check(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String payload = parts[0] + "." + parts[1];
        if (!PhoneHashes.same(hashes.sign(payload), parts[2])) {
            return Optional.empty();
        }
        try {
            if (Long.parseLong(parts[1]) <= clock.instant().getEpochSecond()) {
                return Optional.empty();
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(parts[0]);
    }
}
