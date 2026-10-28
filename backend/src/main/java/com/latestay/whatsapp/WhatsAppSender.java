package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Sends queued messages every few seconds; a failed send is retried up to {@code maxAttempts}. */
@Component
public class WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSender.class);
    static final int BATCH = 20;

    private final WhatsAppMessageRepository messages;
    private final WhatsAppClient client;
    private final WhatsAppProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public WhatsAppSender(WhatsAppMessageRepository messages, WhatsAppClient client, WhatsAppProperties properties,
            ObjectMapper json, Clock clock) {
        this.messages = messages;
        this.client = client;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /** @return how many messages Meta accepted */
    @Transactional
    public int sendQueued() {
        int sent = 0;
        for (WhatsAppMessage message : messages.lockQueued(BATCH)) {
            if (!client.canSend()) {
                messages.notSent(message.id(), clock.instant());
                continue;
            }
            try {
                String waMessageId = client.send(json.readTree(message.payload()));
                messages.sent(message.id(), waMessageId, clock.instant());
                sent++;
            } catch (WhatsAppSendException | IOException e) {
                log.warn("WhatsApp send {} to {} failed (attempt {}): {}", message.id(), message.waId(),
                        message.attempts() + 1, e.getMessage());
                messages.sendFailed(message.id(), e.getMessage(), properties.maxAttempts(), clock.instant());
            }
        }
        return sent;
    }

    @Scheduled(fixedDelayString = "${latestay.whatsapp.send-interval:5s}",
            initialDelayString = "${latestay.whatsapp.send-interval:5s}")
    void tick() {
        try {
            sendQueued();
        } catch (RuntimeException e) {
            log.error("WhatsApp sender failed", e);
        }
    }
}
