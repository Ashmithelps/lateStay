package com.latestay.owner;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner sign-in without passwords. An owner who sends "hisaab" on WhatsApp gets a link with a random token
 * (single use, 15 minutes; only its SHA-256 is stored). Opening it starts a 30-day owner_session for that one
 * hotel, held in an httpOnly cookie (the session id, signed with HMAC). Every owner request re-checks the session
 * row, so a session can be revoked.
 */
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(OwnerProperties.class)
public class OwnerSessions {

    public static final String COOKIE = "ls_owner";
    static final String DEV_SECRET = "latestay-local-development-owner-secret";
    private static final Logger log = LoggerFactory.getLogger(OwnerSessions.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final OwnerProperties properties;
    private final Clock clock;
    private final byte[] secret;

    public OwnerSessions(JdbcClient jdbc, OwnerProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        String s = properties.secret();
        if (s.isBlank()) {
            log.warn("OWNER_SESSION_SECRET is not set: using the development secret");
            s = DEV_SECRET;
        }
        this.secret = s.getBytes(StandardCharsets.UTF_8);
    }

    /** A new sign-in token for the hotel (for the WhatsApp link). */
    @Transactional
    public String newLinkToken(UUID hotelId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO owner_login_token (id, hotel_id, token_hash, expires_at, created_at)
                        VALUES (:id, :h, :hash, :expires, :now)""")
                .param("id", UUID.randomUUID()).param("h", hotelId).param("hash", sha256(token))
                .param("expires", at(now.plus(properties.linkValidity()))).param("now", at(now)).update();
        return token;
    }

    public Duration linkValidity() {
        return properties.linkValidity();
    }

    /** Uses the token (once) and starts a session. @throws OwnerNotSignedInException if used, expired or unknown */
    @Transactional
    public ResponseCookie signIn(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw new OwnerNotSignedInException();
        }
        Instant now = clock.instant();
        record Link(UUID id, UUID hotelId, Instant expiresAt, Instant usedAt) {
        }
        Link link = jdbc.sql("SELECT id, hotel_id, expires_at, used_at FROM owner_login_token "
                        + "WHERE token_hash = :hash FOR UPDATE")
                .param("hash", sha256(token))
                .query((rs, n) -> new Link(rs.getObject("id", UUID.class), rs.getObject("hotel_id", UUID.class),
                        instant(rs.getObject("expires_at", OffsetDateTime.class)),
                        instant(rs.getObject("used_at", OffsetDateTime.class))))
                .optional().orElseThrow(OwnerNotSignedInException::new);
        if (link.usedAt() != null || !now.isBefore(link.expiresAt())) {
            throw new OwnerNotSignedInException();
        }
        jdbc.sql("UPDATE owner_login_token SET used_at = :now WHERE id = :id").param("id", link.id())
                .param("now", at(now)).update();
        UUID sessionId = UUID.randomUUID();
        Instant expires = now.plus(properties.session());
        jdbc.sql("""
                        INSERT INTO owner_session (id, hotel_id, expires_at, created_at)
                        VALUES (:id, :h, :expires, :now)""")
                .param("id", sessionId).param("h", link.hotelId()).param("expires", at(expires))
                .param("now", at(now)).update();
        String value = sessionId + "." + sign(sessionId.toString());
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path("/api/owner").maxAge(properties.session()).build();
    }

    /** The signed-in hotel, if the request carries a valid cookie for a live, unrevoked session of an active hotel. */
    public Optional<OwnerHotel> current(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                return check(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    Optional<OwnerHotel> check(String value) {
        int dot = value == null ? -1 : value.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String id = value.substring(0, dot);
        if (!MessageDigest.isEqual(sign(id).getBytes(StandardCharsets.UTF_8),
                value.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT s.hotel_id FROM owner_session s JOIN hotel h ON h.id = s.hotel_id
                        WHERE s.id = :id AND s.revoked_at IS NULL AND s.expires_at > :now AND h.active""")
                .param("id", sessionId).param("now", at(clock.instant())).query(UUID.class).optional()
                .map(hotelId -> new OwnerHotel(hotelId, sessionId));
    }

    @Transactional
    public ResponseCookie signOut(OwnerHotel owner) {
        jdbc.sql("UPDATE owner_session SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("id", owner.sessionId()).param("now", at(clock.instant())).update();
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path("/api/owner").maxAge(Duration.ZERO).build();
    }

    /** Sign-in links older than a day are deleted (they can't be used after 15 minutes anyway). */
    @Transactional
    public int deleteOldLinks() {
        return jdbc.sql("DELETE FROM owner_login_token WHERE expires_at < :cutoff")
                .param("cutoff", at(clock.instant().minus(Duration.ofDays(1)))).update();
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(("owner-session:" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
