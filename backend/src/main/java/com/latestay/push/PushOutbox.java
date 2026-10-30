package com.latestay.push;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Queues a notification for a booking, once per dedupe key, and only when a device is registered for it. */
@Component
public class PushOutbox {

    private final JdbcClient jdbc;
    private final Devices devices;
    private final Clock clock;

    public PushOutbox(JdbcClient jdbc, Devices devices, Clock clock) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.clock = clock;
    }

    /** @return false when no device is registered for the booking or the message was already queued */
    public boolean queue(UUID bookingId, String kind, String body, String path) {
        if (!devices.anyFor(bookingId)) {
            return false;
        }
        return jdbc.sql("""
                        INSERT INTO push_message (booking_id, kind, dedupe_key, body, path, status, created_at)
                        VALUES (:booking, :kind, :dedupe, :body, :path, 'QUEUED', :now)
                        ON CONFLICT (dedupe_key) DO NOTHING""")
                .param("booking", bookingId).param("kind", kind).param("dedupe", kind + ":" + bookingId)
                .param("body", body).param("path", path).param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update() == 1;
    }
}
