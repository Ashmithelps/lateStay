package com.latestay.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.latestay.TestHotels;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.negotiation.SlotType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Webhook verification, signatures, idempotency and availability replies. The clock reads 22:30 IST, 11 Nov. */
class WhatsAppWebhookIT extends WhatsAppItSupport {

    static final double LAT = 29.0;
    static final LocalDate TONIGHT = LocalDate.of(2026, 11, 11);
    static final java.util.concurrent.atomic.AtomicInteger NEXT_SPOT = new java.util.concurrent.atomic.AtomicInteger();

    Hotel hotel() {
        double lat = LAT + NEXT_SPOT.getAndIncrement() * 0.2;
        Hotel hotel = hotels.hotel("Pine Crest", lat, 79.0, List.of("heater"), new HotelPolicies(true, false, null), true);
        hotels.room(hotel, 1400, 2, 700, SlotType.LATE_NIGHT);
        return hotel;
    }

    // Verification and signatures

    @Test
    void getVerificationEchoesTheChallengeOnlyForOurToken() throws Exception {
        mvc.perform(get("/api/webhooks/whatsapp").param("hub.mode", "subscribe")
                        .param("hub.verify_token", "test_wa_verify_token").param("hub.challenge", "1158201444"))
                .andExpect(status().isOk())
                .andExpect(content().string("1158201444"));
        mvc.perform(get("/api/webhooks/whatsapp").param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong").param("hub.challenge", "1158201444"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/webhooks/whatsapp").param("hub.mode", "unsubscribe")
                        .param("hub.verify_token", "test_wa_verify_token").param("hub.challenge", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unsignedOrBadlySignedDeliveriesAreRejectedAndStoreNothing() throws Exception {
        Hotel hotel = hotel();
        String id = wamid();
        String body = webhook(List.of(text(waId(hotel), id, "rooms 2")), List.of());

        deliver(body, null).andExpect(status().isForbidden());
        deliver(body, WhatsAppSignatures.sign(body.getBytes(StandardCharsets.UTF_8), "not_the_secret"))
                .andExpect(status().isForbidden());
        String signature = WhatsAppSignatures.sign(body.getBytes(StandardCharsets.UTF_8), APP_SECRET);
        deliver(body.replace("rooms 2", "rooms 9"), signature).andExpect(status().isForbidden());

        assertThat(rows(hotel.id())).isEmpty();
        assertThat(reported(hotel.id(), TONIGHT)).isNull();
    }

    // Availability

    @Test
    void aTypedAnswerSetsTonightsRoomsAndIsAcknowledged() throws Exception {
        Hotel hotel = hotel();
        say(hotel, "rooms 2").andExpect(status().isOk()).andExpect(jsonPath("$.handled").value(1));

        assertThat(reported(hotel.id(), TONIGHT)).isEqualTo(2);
        assertThat(outboundTexts(hotel.id())).containsExactly(
                "Got it: 2 rooms free tonight at Pine Crest. We'll message you here when a guest books.");
        assertThat(rows(hotel.id()).getFirst()).containsEntry("direction", "IN").containsEntry("status", "RECEIVED");
    }

    @Test
    void hinglishAnswersAndZeroWork() throws Exception {
        Hotel hotel = hotel();
        say(hotel, "do room khali hai");
        assertThat(reported(hotel.id(), TONIGHT)).isEqualTo(2);
        say(hotel, "koi room nahi");
        assertThat(reported(hotel.id(), TONIGHT)).isZero();
        assertThat(outboundTexts(hotel.id())).last().asString().startsWith("Got it: no rooms tonight at Pine Crest");
    }

    @Test
    void pingButtonsSetTheNightThePingAskedAbout() throws Exception {
        Hotel hotel = hotel();
        tap(hotel, OwnerReplyParser.availabilityPayload(hotel.id(), TONIGHT, "3+"), "3+");
        assertThat(reported(hotel.id(), TONIGHT)).isEqualTo(3);
        tap(hotel, OwnerReplyParser.availabilityPayload(hotel.id(), TONIGHT, "1"), "1");
        assertThat(reported(hotel.id(), TONIGHT)).isEqualTo(1);

        // A tap on last night's ping counts for tonight.
        tap(hotel, OwnerReplyParser.availabilityPayload(hotel.id(), TONIGHT.minusDays(1), "2"), "2");
        assertThat(reported(hotel.id(), TONIGHT)).isEqualTo(2);
        assertThat(reported(hotel.id(), TONIGHT.minusDays(1))).isNull();
    }

    @Test
    void aHotelThatHasNotAnsweredTonightIsHiddenFromSearch() throws Exception {
        Hotel hotel = hotel();
        String search = "/api/search?lat=" + hotel.lat() + "&lng=" + hotel.lng()
                + "&arrive=2026-11-11T23:30&leave=2026-11-12T09:00&guests=2";
        assertThat(mvc.perform(get(search)).andReturn().getResponse().getContentAsString()).doesNotContain(hotel.slug());

        say(hotel, "1 room khali");
        assertThat(mvc.perform(get(search)).andReturn().getResponse().getContentAsString()).contains(hotel.slug());

        // Early-morning arrivals belong to the same night, so the evening answer covers them too.
        String morning = "/api/search?lat=" + hotel.lat() + "&lng=" + hotel.lng()
                + "&arrive=2026-11-12T23:30&leave=2026-11-13T09:00&guests=2";
        assertThat(mvc.perform(get(morning)).andReturn().getResponse().getContentAsString()).doesNotContain(hotel.slug());
    }

    @Test
    void anUnclearMessageGetsHelpAndChangesNothing() throws Exception {
        Hotel hotel = hotel();
        say(hotel, "kal baat karte hain").andExpect(jsonPath("$.handled").value(1));
        assertThat(reported(hotel.id(), TONIGHT)).isNull();
        assertThat(outboundTexts(hotel.id())).containsExactly(OwnerMessageHandler.HELP);
        assertThat(rows(hotel.id()).getFirst()).containsEntry("error", "not understood");
    }

    @Test
    void anOwnerOfTwoHotelsMustUseTheButton() throws Exception {
        Hotel first = hotel();
        Hotel second = hotels.hotel("Pine Crest Annexe", first.lat() + 0.01, first.lng(), List.of(),
                HotelPolicies.NONE, true);
        jdbc.sql("UPDATE hotel SET whatsapp_number = :n WHERE id = :id").param("n", first.whatsappNumber())
                .param("id", second.id()).update();

        say(first, "rooms 2");
        assertThat(reported(first.id(), TONIGHT)).isNull();
        assertThat(reported(second.id(), TONIGHT)).isNull();
        assertThat(outboundTexts(first.id()).getFirst()).startsWith("You manage more than one hotel");

        tap(first, OwnerReplyParser.availabilityPayload(second.id(), TONIGHT, "2"), "2");
        assertThat(reported(second.id(), TONIGHT)).isEqualTo(2);
        assertThat(reported(first.id(), TONIGHT)).isNull();
    }

    @Test
    void aButtonForAHotelOnAnotherNumberIsRefused() throws Exception {
        Hotel mine = hotel();
        Hotel theirs = hotel();
        tap(mine, OwnerReplyParser.availabilityPayload(theirs.id(), TONIGHT, "2"), "2");
        assertThat(reported(theirs.id(), TONIGHT)).isNull();
    }

    @Test
    void unknownNumbersAreLoggedAndIgnored() throws Exception {
        String stranger = "91" + TestHotels.uniqueWhatsAppNumber().replaceAll("[^0-9]", "").substring(2);
        String id = wamid();
        deliver(webhook(List.of(text(stranger, id, "rooms 2")), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(1));
        Map<String, Object> row = jdbc.sql("SELECT * FROM whatsapp_message WHERE wa_message_id = :id").param("id", id)
                .query().singleRow();
        assertThat(row).containsEntry("direction", "IN").containsEntry("status", "IGNORED")
                .containsEntry("wa_id", stranger);
        assertThat(row.get("hotel_id")).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM whatsapp_message WHERE wa_id = :wa AND direction = 'OUT'")
                .param("wa", stranger).query(Integer.class).single()).isZero();
    }

    // Idempotency

    @Test
    void aRedeliveredMessageIsHandledOnce() throws Exception {
        Hotel hotel = hotel();
        String body = webhook(List.of(text(waId(hotel), wamid(), "rooms 2")), List.of());

        deliver(body).andExpect(jsonPath("$.handled").value(1));
        // The admin corrects the number in between; Meta's retry of the old message must not undo it.
        availability.upsert(hotel.id(), TONIGHT, 5, com.latestay.hotel.AvailabilitySource.ADMIN);
        deliver(body).andExpect(jsonPath("$.handled").value(0)).andExpect(jsonPath("$.duplicates").value(1));
        deliver(body).andExpect(jsonPath("$.duplicates").value(1));

        assertThat(availability.roomsFree(hotel.id(), TONIGHT)).isEqualTo(5);
        assertThat(rows(hotel.id()).stream().filter(r -> r.get("direction").equals("IN"))).hasSize(1);
        assertThat(outboundTexts(hotel.id())).hasSize(1);
    }

    @Test
    void deliveryStatusesOnlyMoveForward() throws Exception {
        Hotel hotel = hotel();
        pings.pingNow(hotel.id());
        drain();
        Map<String, Object> ping = rows(hotel.id()).getFirst();
        assertThat(ping).containsEntry("status", "SENT").containsEntry("template", "nightly_availability");
        String id = (String) ping.get("wa_message_id");

        deliver(webhook(List.of(), List.of(statusEvent(id, "delivered")))).andExpect(jsonPath("$.statuses").value(1));
        deliver(webhook(List.of(), List.of(statusEvent(id, "delivered")))).andExpect(jsonPath("$.statuses").value(0));
        deliver(webhook(List.of(), List.of(statusEvent(id, "sent")))).andExpect(jsonPath("$.statuses").value(0));
        assertThat(rows(hotel.id()).getFirst()).containsEntry("status", "DELIVERED");
        deliver(webhook(List.of(), List.of(statusEvent(id, "read"))));
        deliver(webhook(List.of(), List.of(statusEvent(id, "failed"))));
        assertThat(rows(hotel.id()).getFirst()).containsEntry("status", "READ");
    }

    @Test
    void aFailedDeliveryIsRecordedWithMetasError() throws Exception {
        Hotel hotel = hotel();
        pings.pingNow(hotel.id());
        drain();
        String id = (String) rows(hotel.id()).getFirst().get("wa_message_id");
        String failed = """
                {"id":"%s","status":"failed","timestamp":"1794416500","recipient_id":"919000000000",\
                "errors":[{"code":131047,"title":"Re-engagement message"}]}""".formatted(id);
        deliver(webhook(List.of(), List.of(failed)));
        assertThat(rows(hotel.id()).getFirst()).containsEntry("status", "FAILED")
                .hasEntrySatisfying("error", e -> assertThat((String) e).contains("131047"));
    }

    @Test
    void oneDeliveryCanCarrySeveralMessages() throws Exception {
        Hotel a = hotel();
        Hotel b = hotel();
        deliver(webhook(List.of(text(waId(a), wamid(), "rooms 1"), text(waId(b), wamid(), "3 rooms free")), List.of()))
                .andExpect(jsonPath("$.handled").value(2));
        assertThat(reported(a.id(), TONIGHT)).isEqualTo(1);
        assertThat(reported(b.id(), TONIGHT)).isEqualTo(3);
    }

    @Test
    void theNightlyPingAsksEveryActiveHotelOncePerNight() {
        Hotel active = hotel();
        Hotel inactive = hotels.hotel("Closed Inn", LAT + 50, 79.0, List.of(), HotelPolicies.NONE, false);

        pings.pingAll();
        pings.pingAll();
        assertThat(rows(active.id())).hasSize(1);
        assertThat(rows(inactive.id())).isEmpty();

        JsonNode ping = read(rows(active.id()).getFirst().get("payload"));
        assertThat(ping.path("to").asText()).isEqualTo(waId(active));
        assertThat(ping.path("template").path("name").asText()).isEqualTo("nightly_availability");
        assertThat(ping.path("template").path("components").get(0).path("parameters").get(0).path("text").asText())
                .isEqualTo("Pine Crest");
        List<String> payloads = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            JsonNode button = ping.path("template").path("components").get(i);
            assertThat(button.path("sub_type").asText()).isEqualTo("quick_reply");
            assertThat(button.path("index").asText()).isEqualTo(String.valueOf(i - 1));
            payloads.add(button.path("parameters").get(0).path("payload").asText());
        }
        assertThat(payloads).containsExactly(
                OwnerReplyParser.availabilityPayload(active.id(), TONIGHT, "0"),
                OwnerReplyParser.availabilityPayload(active.id(), TONIGHT, "1"),
                OwnerReplyParser.availabilityPayload(active.id(), TONIGHT, "2"),
                OwnerReplyParser.availabilityPayload(active.id(), TONIGHT, "3+"));

        // The admin's "ping now" always sends another.
        pings.pingNow(active.id());
        assertThat(rows(active.id())).hasSize(2);
    }

    @Test
    void sendingIsRetriedAfterAGraphApiFailure() {
        Hotel hotel = hotel();
        drain();
        pings.pingNow(hotel.id());
        whatsApp.failNext.set(1);

        sender.sendQueued();
        assertThat(rows(hotel.id()).getFirst()).containsEntry("status", "QUEUED")
                .hasEntrySatisfying("error", e -> assertThat((String) e).contains("500"));
        sender.sendQueued();
        assertThat(rows(hotel.id()).getFirst()).containsEntry("status", "SENT").containsEntry("error", null);
        assertThat(whatsApp.sent).anyMatch(m -> m.path("to").asText().equals(waId(hotel)));
    }

    @Test
    void adminSeesTheLogAndCanPingNow() throws Exception {
        Hotel hotel = hotel();
        say(hotel, "rooms 2");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/hotels/{id}/whatsapp/availability-ping", hotel.id())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/admin/hotels/{id}/whatsapp", hotel.id())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].template").value("nightly_availability"))
                .andExpect(jsonPath("$[0].summary").value("nightly_availability (Pine Crest)"))
                .andExpect(jsonPath("$[1].direction").value("OUT"))
                .andExpect(jsonPath("$[2].direction").value("IN"))
                .andExpect(jsonPath("$[2].summary").value("rooms 2"));
        mvc.perform(get("/api/admin/hotels/{id}/whatsapp", hotel.id())).andExpect(status().isUnauthorized());
    }
}
