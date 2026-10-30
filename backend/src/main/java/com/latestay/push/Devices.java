package com.latestay.push;

import com.latestay.hotel.NotFoundException;
import com.latestay.phone.PhoneHashes;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** App installs' FCM tokens and the bookings they asked to hear about. */
@Component
public class Devices {

    private final JdbcClient jdbc;
    private final PhoneHashes phoneHashes;
    private final Clock clock;

    public Devices(JdbcClient jdbc, PhoneHashes phoneHashes, Clock clock) {
        this.jdbc = jdbc;
        this.phoneHashes = phoneHashes;
        this.clock = clock;
    }

    /**
     * Registers (or refreshes) a token, for a booking when one is given and for the verified number on this device
     * when there is one.
     */
    @Transactional
    public void register(String token, PushPlatform platform, UUID bookingId, String phoneHash) {
        OffsetDateTime now = at(clock.instant());
        if (bookingId != null && jdbc.sql("SELECT count(*) FROM booking WHERE id = :id").param("id", bookingId)
                .query(Integer.class).single() == 0) {
            throw new NotFoundException("booking", bookingId);
        }
        UUID id = jdbc.sql("""
                        INSERT INTO device_token (id, token, platform, phone_hash, created_at, last_seen_at)
                        VALUES (:id, :token, :platform, :phone, :now, :now)
                        ON CONFLICT (token) DO UPDATE SET platform = EXCLUDED.platform, last_seen_at = EXCLUDED.last_seen_at,
                            phone_hash = COALESCE(EXCLUDED.phone_hash, device_token.phone_hash)
                        RETURNING id""")
                .param("id", UUID.randomUUID()).param("token", token).param("platform", platform.name())
                .param("phone", phoneHash).param("now", now)
                .query(UUID.class).single();
        if (bookingId != null) {
            jdbc.sql("""
                            INSERT INTO device_booking (device_id, booking_id, created_at) VALUES (:device, :booking, :now)
                            ON CONFLICT DO NOTHING""")
                    .param("device", id).param("booking", bookingId).param("now", now).update();
        }
    }

    /** Tokens to notify about a booking: registered for it, or for its (verified) phone number. */
    public List<String> tokensFor(UUID bookingId) {
        String phone = jdbc.sql("SELECT phone FROM booking WHERE id = :id").param("id", bookingId)
                .query(String.class).optional().orElse(null);
        String hash = phone == null ? null : phoneHashes.phone(phone);
        return jdbc.sql("""
                        SELECT d.token FROM device_token d
                        WHERE EXISTS (SELECT 1 FROM device_booking db WHERE db.device_id = d.id AND db.booking_id = :booking)
                           OR (CAST(:hash AS text) IS NOT NULL AND d.phone_hash = :hash)
                        ORDER BY d.created_at""")
                .param("booking", bookingId).param("hash", hash)
                .query(String.class).list();
    }

    public boolean anyFor(UUID bookingId) {
        return !tokensFor(bookingId).isEmpty();
    }

    /** FCM said this token is gone. */
    public void forget(String token) {
        jdbc.sql("DELETE FROM device_token WHERE token = :token").param("token", token).update();
    }

    /** @return how many tokens not seen since {@code before} were deleted */
    public int deleteNotSeenSince(Instant before) {
        return jdbc.sql("DELETE FROM device_token WHERE last_seen_at < :before").param("before", at(before)).update();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
