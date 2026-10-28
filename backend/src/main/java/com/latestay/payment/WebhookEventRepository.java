package com.latestay.payment;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookEventRepository {

    private final JdbcClient jdbc;

    public WebhookEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a delivery; false if this event id was already recorded (a Razorpay retry). */
    public boolean firstDelivery(String eventId, String event, Instant now) {
        return jdbc.sql("""
                        INSERT INTO razorpay_webhook_event (event_id, event, received_at) VALUES (:id, :event, :now)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("id", eventId).param("event", event).param("now", now.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }
}
