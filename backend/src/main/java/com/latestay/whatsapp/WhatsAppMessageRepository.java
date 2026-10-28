package com.latestay.whatsapp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WhatsAppMessageRepository {

    /** A hotel an owner's number belongs to. */
    public record OwnedHotel(UUID id, String name, boolean active) {
    }

    private static final String COLUMNS = """
            id, direction, hotel_id, wa_id, wa_message_id, type, template, payload::text AS payload, status, error,
            attempts, booking_id, created_at, updated_at""";

    private final JdbcClient jdbc;

    public WhatsAppMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Hotels whose WhatsApp number (digits only) is {@code waId}. One owner can run several. */
    public List<OwnedHotel> hotelsFor(String waId) {
        return jdbc.sql("SELECT id, name, active FROM hotel WHERE whatsapp_wa_id = :wa ORDER BY active DESC, name")
                .param("wa", waId)
                .query((rs, n) -> new OwnedHotel(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("active")))
                .list();
    }

    /** Stores an inbound message; false if this wa_message_id was already stored (a webhook redelivery). */
    public boolean insertInbound(String waMessageId, UUID hotelId, String waId, String type, String payload,
            String status, Instant now) {
        return jdbc.sql("""
                        INSERT INTO whatsapp_message (direction, hotel_id, wa_id, wa_message_id, type, payload, status,
                               created_at, updated_at)
                        VALUES ('IN', :hotel, :wa, :wamid, :type, CAST(:payload AS jsonb), :status, :now, :now)
                        ON CONFLICT (wa_message_id) DO NOTHING""")
                .param("hotel", hotelId).param("wa", waId).param("wamid", waMessageId).param("type", type)
                .param("payload", payload).param("status", status).param("now", at(now))
                .update() == 1;
    }

    public void inboundOutcome(String waMessageId, String status, String error, UUID bookingId, Instant now) {
        jdbc.sql("""
                        UPDATE whatsapp_message SET status = :status, error = :error,
                               booking_id = COALESCE(:booking, booking_id), updated_at = :now
                        WHERE wa_message_id = :wamid AND direction = 'IN'""")
                .param("wamid", waMessageId).param("status", status).param("error", error)
                .param("booking", bookingId).param("now", at(now)).update();
    }

    /** Queues an outbound message; false if one with this dedupe key was already queued. */
    public boolean queue(UUID hotelId, String waId, String type, String template, String payload, UUID bookingId,
            String dedupeKey, Instant now) {
        return jdbc.sql("""
                        INSERT INTO whatsapp_message (direction, hotel_id, wa_id, type, template, payload, status,
                               booking_id, dedupe_key, created_at, updated_at)
                        VALUES ('OUT', :hotel, :wa, :type, :template, CAST(:payload AS jsonb), 'QUEUED', :booking,
                                :dedupe, :now, :now)
                        ON CONFLICT (dedupe_key) DO NOTHING""")
                .param("hotel", hotelId).param("wa", waId).param("type", type).param("template", template)
                .param("payload", payload).param("booking", bookingId).param("dedupe", dedupeKey)
                .param("now", at(now))
                .update() == 1;
    }

    /** Queued messages, oldest first, locked; rows another sender holds are skipped. */
    public List<WhatsAppMessage> lockQueued(int limit) {
        return jdbc.sql("""
                        SELECT %s FROM whatsapp_message WHERE status = 'QUEUED' ORDER BY id LIMIT :limit
                        FOR UPDATE SKIP LOCKED""".formatted(COLUMNS))
                .param("limit", limit).query(WhatsAppMessageRepository::map).list();
    }

    public void sent(long id, String waMessageId, Instant now) {
        jdbc.sql("""
                        UPDATE whatsapp_message SET status = 'SENT', wa_message_id = :wamid,
                               attempts = attempts + 1, error = NULL, updated_at = :now
                        WHERE id = :id""")
                .param("id", id).param("wamid", waMessageId).param("now", at(now)).update();
    }

    /** A failed attempt; after {@code maxAttempts} the message is FAILED for good. */
    public void sendFailed(long id, String error, int maxAttempts, Instant now) {
        jdbc.sql("""
                        UPDATE whatsapp_message SET attempts = attempts + 1, error = :error,
                               status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE status END,
                               updated_at = :now
                        WHERE id = :id""")
                .param("id", id).param("error", error).param("max", maxAttempts).param("now", at(now)).update();
    }

    public void notSent(long id, Instant now) {
        jdbc.sql("""
                        UPDATE whatsapp_message SET status = 'NOT_SENT', error = 'WhatsApp is not configured',
                               updated_at = :now
                        WHERE id = :id""")
                .param("id", id).param("now", at(now)).update();
    }

    /**
     * Applies a delivery status from the webhook. Statuses only move forward (SENT, DELIVERED, READ), so a late or
     * repeated status changes nothing; FAILED applies unless the message was already delivered or read.
     */
    public boolean status(String waMessageId, String status, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE whatsapp_message SET status = :status, error = COALESCE(:error, error), updated_at = :now
                        WHERE wa_message_id = :wamid AND direction = 'OUT'
                          AND array_position(ARRAY['QUEUED', 'NOT_SENT', 'SENT', 'DELIVERED', 'READ'], status)
                              < CASE :status WHEN 'FAILED' THEN 4
                                    ELSE array_position(ARRAY['QUEUED', 'NOT_SENT', 'SENT', 'DELIVERED', 'READ'],
                                         CAST(:status AS text)) END""")
                .param("wamid", waMessageId).param("status", status).param("error", error).param("now", at(now))
                .update() == 1;
    }

    public List<WhatsAppMessage> forHotel(UUID hotelId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM whatsapp_message WHERE hotel_id = :hotel ORDER BY id DESC "
                        + "LIMIT :limit")
                .param("hotel", hotelId).param("limit", limit).query(WhatsAppMessageRepository::map).list();
    }

    public List<WhatsAppMessage> forBooking(UUID bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM whatsapp_message WHERE booking_id = :booking ORDER BY id")
                .param("booking", bookingId).query(WhatsAppMessageRepository::map).list();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static WhatsAppMessage map(ResultSet rs, int n) throws SQLException {
        return new WhatsAppMessage(rs.getLong("id"), rs.getString("direction"), rs.getObject("hotel_id", UUID.class),
                rs.getString("wa_id"), rs.getString("wa_message_id"), rs.getString("type"), rs.getString("template"),
                rs.getString("payload"), rs.getString("status"), rs.getString("error"), rs.getInt("attempts"),
                rs.getObject("booking_id", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));
    }
}
