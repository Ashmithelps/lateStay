package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A whatsapp_message row for the admin log, with a one-line human summary of the payload. */
public record WhatsAppLogEntry(long id, String direction, String waId, String type, String template, String summary,
        String status, String error, UUID bookingId, Instant at) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static WhatsAppLogEntry of(WhatsAppMessage m) {
        return new WhatsAppLogEntry(m.id(), m.direction(), m.waId(), m.type(), m.template(), summary(m), m.status(),
                m.error(), m.bookingId(), m.updatedAt());
    }

    static String summary(WhatsAppMessage m) {
        try {
            JsonNode p = JSON.readTree(m.payload());
            if (p.has("text")) {
                return p.path("text").path("body").asText();
            }
            if (p.has("button")) {
                return "Tapped \"" + p.path("button").path("text").asText() + "\"";
            }
            if (p.has("interactive")) {
                return "Tapped \"" + p.path("interactive").path("button_reply").path("title").asText() + "\"";
            }
            if (p.has("template")) {
                List<String> values = new ArrayList<>();
                for (JsonNode component : p.path("template").path("components")) {
                    if ("body".equals(component.path("type").asText())) {
                        component.path("parameters").forEach(v -> values.add(v.path("text").asText()));
                    }
                }
                return p.path("template").path("name").asText() + " (" + String.join(", ", values) + ")";
            }
            return m.type();
        } catch (IOException e) {
            return m.type();
        }
    }
}
