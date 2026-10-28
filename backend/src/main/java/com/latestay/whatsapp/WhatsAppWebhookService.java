package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.whatsapp.WhatsAppMessageRepository.OwnedHotel;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WhatsApp Cloud API webhooks. Every inbound message is stored first, keyed by its wa_message_id, so a
 * redelivery does nothing; the whole delivery is one transaction, so a failure is retried in full by Meta.
 */
@Service
public class WhatsAppWebhookService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookService.class);

    /** What one delivery did, for logs and tests. */
    public record Result(int handled, int duplicates, int ignored, int statuses) {
    }

    public static class InvalidSignatureException extends RuntimeException {
        InvalidSignatureException() {
            super("invalid X-Hub-Signature-256");
        }
    }

    public static class NotConfiguredException extends RuntimeException {
        NotConfiguredException(String what) {
            super(what + " is not set");
        }
    }

    private final WhatsAppProperties properties;
    private final WhatsAppMessageRepository messages;
    private final OwnerMessageHandler handler;
    private final ObjectMapper json;
    private final Clock clock;

    public WhatsAppWebhookService(WhatsAppProperties properties, WhatsAppMessageRepository messages,
            OwnerMessageHandler handler, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.messages = messages;
        this.handler = handler;
        this.json = json;
        this.clock = clock;
    }

    /** GET verification: echo the challenge only for our verify token. */
    public String verify(String mode, String token, String challenge) {
        if (properties.verifyToken().isBlank()) {
            throw new NotConfiguredException("WA_VERIFY_TOKEN");
        }
        if (!"subscribe".equals(mode) || !properties.verifyToken().equals(token) || challenge == null) {
            throw new InvalidSignatureException();
        }
        return challenge;
    }

    @Transactional
    public Result receive(byte[] body, String signature) {
        if (properties.appSecret().isBlank()) {
            throw new NotConfiguredException("WA_APP_SECRET");
        }
        if (!WhatsAppSignatures.valid(body, signature, properties.appSecret())) {
            throw new InvalidSignatureException();
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("webhook body isn't JSON");
        }
        int handled = 0;
        int duplicates = 0;
        int ignored = 0;
        int statuses = 0;
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                for (JsonNode message : value.path("messages")) {
                    switch (inbound(message)) {
                        case HANDLED -> handled++;
                        case DUPLICATE -> duplicates++;
                        case IGNORED -> ignored++;
                    }
                }
                for (JsonNode status : value.path("statuses")) {
                    if (messages.status(status.path("id").asText(), status(status.path("status").asText()),
                            status.path("errors").isMissingNode() ? null : status.path("errors").toString(),
                            clock.instant())) {
                        statuses++;
                    }
                }
            }
        }
        return new Result(handled, duplicates, ignored, statuses);
    }

    /** Local testing without Meta: handles a message object as if it had arrived in a signed webhook. */
    @Transactional
    public String simulateInbound(JsonNode message) {
        return inbound(message).name();
    }

    private enum Inbound { HANDLED, DUPLICATE, IGNORED }

    /** One inbound message: store (idempotent), then match the number, parse and act. */
    Inbound inbound(JsonNode message) {
        String waMessageId = message.path("id").asText();
        String from = message.path("from").asText();
        String type = message.path("type").asText("unknown");
        List<OwnedHotel> owned = messages.hotelsFor(from);
        if (owned.isEmpty()) {
            boolean stored = messages.insertInbound(waMessageId, null, from, type, message.toString(), "IGNORED",
                    clock.instant());
            if (stored) {
                log.info("WhatsApp message from unknown number {} ignored", from);
            }
            return stored ? Inbound.IGNORED : Inbound.DUPLICATE;
        }
        if (!messages.insertInbound(waMessageId, owned.getFirst().id(), from, type, message.toString(), "RECEIVED",
                clock.instant())) {
            return Inbound.DUPLICATE;
        }
        OwnerReply reply = OwnerReplyParser.parse(buttonPayload(message), text(message));
        OwnerMessageHandler.Outcome outcome = handler.handle(from, waMessageId, owned, reply);
        messages.inboundOutcome(waMessageId, "RECEIVED", outcome.error(), outcome.bookingId(), clock.instant());
        return Inbound.HANDLED;
    }

    /** Template quick replies arrive as type "button"; interactive reply buttons as "interactive". */
    static String buttonPayload(JsonNode message) {
        if (message.has("button")) {
            return message.path("button").path("payload").asText(null);
        }
        JsonNode reply = message.path("interactive").path("button_reply");
        return reply.isMissingNode() ? null : reply.path("id").asText(null);
    }

    static String text(JsonNode message) {
        if (message.has("text")) {
            return message.path("text").path("body").asText(null);
        }
        if (message.has("button")) {
            return message.path("button").path("text").asText(null);
        }
        JsonNode reply = message.path("interactive").path("button_reply");
        return reply.isMissingNode() ? null : reply.path("title").asText(null);
    }

    private static String status(String whatsAppStatus) {
        return switch (whatsAppStatus) {
            case "sent" -> "SENT";
            case "delivered" -> "DELIVERED";
            case "read" -> "READ";
            case "failed" -> "FAILED";
            default -> "SENT";
        };
    }
}
