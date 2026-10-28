package com.latestay.whatsapp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.booking.BookingItSupport;
import com.latestay.hotel.Hotel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Builds and posts signed Cloud API webhooks. Same Spring context as the booking ITs. */
abstract class WhatsAppItSupport extends BookingItSupport {

    static final String APP_SECRET = "test_wa_app_secret";

    @Autowired
    ObjectMapper json;
    @Autowired
    RecordingWhatsAppClient whatsApp;
    @Autowired
    WhatsAppSender sender;
    @Autowired
    AvailabilityPings pings;

    @BeforeEach
    void resetWhatsApp() {
        whatsApp.reset();
    }

    /** Sends everything queued (other tests leave messages behind, and the sender works in batches). */
    void drain() {
        for (int i = 0; i < 100 && jdbc.sql("SELECT count(*) FROM whatsapp_message WHERE status = 'QUEUED'")
                .query(Integer.class).single() > 0; i++) {
            sender.sendQueued();
        }
    }

    static String waId(Hotel hotel) {
        return hotel.whatsappNumber().replaceAll("[^0-9]", "");
    }

    static String wamid() {
        return "wamid.in." + UUID.randomUUID();
    }

    static String text(String from, String id, String body) {
        return """
                {"from":"%s","id":"%s","timestamp":"1794416400","type":"text","text":{"body":%s}}"""
                .formatted(from, id, quote(body));
    }

    static String button(String from, String id, String payload, String label) {
        return """
                {"from":"%s","id":"%s","timestamp":"1794416400","type":"button",\
                "context":{"from":"15550001111","id":"wamid.out.ping"},"button":{"payload":%s,"text":%s}}"""
                .formatted(from, id, quote(payload), quote(label));
    }

    static String statusEvent(String wamid, String status) {
        return """
                {"id":"%s","status":"%s","timestamp":"1794416500","recipient_id":"919000000000"}"""
                .formatted(wamid, status);
    }

    /** A Cloud API "messages" webhook carrying these message and status objects. */
    static String webhook(List<String> messages, List<String> statuses) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"102290129340398","changes":[{"field":"messages",\
                "value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550001111",\
                "phone_number_id":"106540352242922"},"contacts":[{"profile":{"name":"Owner"},"wa_id":"919000000000"}],\
                "messages":[%s],"statuses":[%s]}}]}]}"""
                .formatted(String.join(",", messages), String.join(",", statuses));
    }

    ResultActions deliver(String body) throws Exception {
        return deliver(body, WhatsAppSignatures.sign(body.getBytes(StandardCharsets.UTF_8), APP_SECRET));
    }

    ResultActions deliver(String body, String signature) throws Exception {
        var request = post("/api/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return mvc.perform(request);
    }

    ResultActions say(Hotel hotel, String text) throws Exception {
        return deliver(webhook(List.of(text(waId(hotel), wamid(), text)), List.of()));
    }

    ResultActions tap(Hotel hotel, String payload, String label) throws Exception {
        return deliver(webhook(List.of(button(waId(hotel), wamid(), payload, label)), List.of()));
    }

    List<Map<String, Object>> rows(UUID hotelId) {
        return jdbc.sql("""
                        SELECT direction, type, template, status, error, wa_message_id, booking_id, dedupe_key,
                               payload::text AS payload
                        FROM whatsapp_message WHERE hotel_id = :hotel ORDER BY id""")
                .param("hotel", hotelId).query().listOfRows();
    }

    List<String> outboundTexts(UUID hotelId) {
        return rows(hotelId).stream().filter(r -> r.get("direction").equals("OUT") && r.get("type").equals("text"))
                .map(r -> read(r.get("payload")).path("text").path("body").asText()).toList();
    }

    JsonNode read(Object payload) {
        try {
            return json.readTree((String) payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Integer reported(UUID hotelId, java.time.LocalDate night) {
        return jdbc.sql("SELECT rooms_free FROM nightly_availability WHERE hotel_id = :h AND night_of = :n AND "
                        + "source = 'WHATSAPP'")
                .param("h", hotelId).param("n", night).query(Integer.class).optional().orElse(null);
    }

    private static String quote(String value) {
        return com.latestay.conversation.PromptText.quote(value);
    }
}
