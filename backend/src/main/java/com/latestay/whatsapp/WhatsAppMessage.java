package com.latestay.whatsapp;

import java.time.Instant;
import java.util.UUID;

/** A row of whatsapp_message. {@code payload} is JSON text. */
public record WhatsAppMessage(long id, String direction, UUID hotelId, String waId, String waMessageId, String type,
        String template, String payload, String status, String error, int attempts, UUID bookingId,
        Instant createdAt, Instant updatedAt) {
}
