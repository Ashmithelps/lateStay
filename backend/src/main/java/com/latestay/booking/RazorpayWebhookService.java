package com.latestay.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.payment.PaymentsUnavailableException;
import com.latestay.payment.RazorpayProperties;
import com.latestay.payment.RazorpaySignatures;
import com.latestay.payment.WebhookEventRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Razorpay webhooks. The signature is checked against the raw body; each event id is handled once (recorded in
 * the same transaction, so a failed delivery is retried in full); and the handlers themselves are idempotent, so
 * two different events about one payment also change things only once.
 */
@Service
public class RazorpayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);

    public enum Outcome { PROCESSED, DUPLICATE, IGNORED }

    private final RazorpayProperties properties;
    private final WebhookEventRepository events;
    private final BookingService bookings;
    private final ObjectMapper json;
    private final Clock clock;

    public RazorpayWebhookService(RazorpayProperties properties, WebhookEventRepository events,
            BookingService bookings, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.events = events;
        this.bookings = bookings;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Outcome handle(byte[] body, String signature, String eventId) {
        if (properties.webhookSecret().isBlank()) {
            throw new PaymentsUnavailableException();
        }
        if (!RazorpaySignatures.webhookValid(body, signature, properties.webhookSecret())) {
            throw new InvalidBookingException("invalid webhook signature");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new InvalidBookingException("webhook body isn't JSON");
        }
        String event = root.path("event").asText();
        String id = eventId == null || eventId.isBlank() ? "sha256:" + sha256(body) : eventId;
        if (!events.firstDelivery(id, event, clock.instant())) {
            log.info("Duplicate Razorpay webhook {} ({})", id, event);
            return Outcome.DUPLICATE;
        }
        String raw = new String(body, StandardCharsets.UTF_8);
        switch (event) {
            case "payment.captured" -> {
                JsonNode payment = root.path("payload").path("payment").path("entity");
                bookings.paymentCaptured(payment.path("order_id").asText(), payment.path("id").asText(),
                        payment.path("amount").asLong() / 100, raw);
                return Outcome.PROCESSED;
            }
            case "refund.processed" -> {
                JsonNode refund = root.path("payload").path("refund").path("entity");
                bookings.refundProcessed(refund.path("payment_id").asText(), refund.path("id").asText(), raw);
                return Outcome.PROCESSED;
            }
            default -> {
                return Outcome.IGNORED;
            }
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
