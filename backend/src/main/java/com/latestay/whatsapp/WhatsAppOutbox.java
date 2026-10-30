package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Queues outbound messages in whatsapp_message, inside the caller's transaction. {@link WhatsAppSender} sends
 * them, so no Graph API call ever runs inside a booking transaction.
 */
@Component
public class WhatsAppOutbox {

    private final WhatsAppMessageRepository messages;
    private final Clock clock;

    public WhatsAppOutbox(WhatsAppMessageRepository messages, Clock clock) {
        this.messages = messages;
        this.clock = clock;
    }

    /** Who a message is for: owners' rows show in the hotel's log; travellers' numbers are redacted later. */
    public enum Audience { OWNER, TRAVELLER, ADMIN }

    /** @return false if a message with this dedupe key was already queued */
    public boolean queue(UUID hotelId, JsonNode message, UUID bookingId, String dedupeKey) {
        return queue(hotelId, message, bookingId, dedupeKey, Audience.OWNER);
    }

    public boolean queue(UUID hotelId, JsonNode message, UUID bookingId, String dedupeKey, Audience audience) {
        String type = message.path("type").asText();
        String template = type.equals("template") ? message.path("template").path("name").asText() : null;
        return messages.queue(hotelId, message.path("to").asText(), type, template, message.toString(), bookingId,
                dedupeKey, clock.instant(), audience.name());
    }

    /** WhatsApp ids are the number's digits, without + or spaces. */
    public static String waId(String number) {
        return number.replaceAll("[^0-9]", "");
    }
}
