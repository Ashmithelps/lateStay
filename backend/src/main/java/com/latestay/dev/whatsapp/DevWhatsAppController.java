package com.latestay.dev.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.latestay.whatsapp.WhatsAppWebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local profile only: plays an owner replying on WhatsApp, without Meta or a tunnel. Pass {@code text} for a typed
 * message, or {@code buttonPayload} (copied from a queued template in the admin log) for a button tap.
 */
@Profile("local")
@RestController
class DevWhatsAppController {

    record InboundBody(@NotBlank String from, String text, String buttonPayload, String buttonText) {
    }

    private final WhatsAppWebhookService webhooks;
    private final ObjectMapper json;

    DevWhatsAppController(WhatsAppWebhookService webhooks, ObjectMapper json) {
        this.webhooks = webhooks;
        this.json = json;
    }

    @PostMapping("/api/dev/whatsapp/inbound")
    Map<String, String> inbound(@Valid @RequestBody InboundBody body) {
        ObjectNode message = json.createObjectNode();
        message.put("from", body.from().replaceAll("[^0-9]", ""));
        message.put("id", "wamid.dev." + UUID.randomUUID());
        message.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        if (body.buttonPayload() != null && !body.buttonPayload().isBlank()) {
            message.put("type", "button");
            message.putObject("button").put("payload", body.buttonPayload())
                    .put("text", body.buttonText() == null ? "" : body.buttonText());
        } else {
            message.put("type", "text");
            message.putObject("text").put("body", body.text() == null ? "" : body.text());
        }
        return Map.of("outcome", webhooks.simulateInbound(message), "id", message.path("id").asText());
    }
}
