package com.latestay.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.TestHotels;
import com.latestay.TestcontainersConfiguration;
import com.latestay.conversation.PromptText;
import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.RoomType;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.SessionStatus;
import com.latestay.negotiation.SlotType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestHotels.class})
class NegotiationPersistenceIT {

    static final double LAT = 31.60;
    static final double LNG = 78.60;
    static final LocalDate NIGHT = LocalDate.of(2026, 11, 11);
    static final java.util.concurrent.atomic.AtomicInteger NEXT_SPOT = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    TestHotels fixtures;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FloorRuleRepository floorRules;
    @Autowired
    NegotiationEngine engine;
    @Autowired
    TransactionTemplate tx;

    Hotel hotel;
    RoomType room;

    @BeforeEach
    void hotel() {
        // Each test gets its own hotel about 22 km from the previous one, so searches don't see each other's.
        double lat = LAT + NEXT_SPOT.getAndIncrement() * 0.2;
        hotel = fixtures.hotel("Persist Lodge", lat, LNG, List.of("heater"), new HotelPolicies(true, false, null), true);
        room = fixtures.room(hotel, 1400, 2, 700, SlotType.LATE_NIGHT);
        fixtures.roomsFree(hotel, NIGHT, 2);
    }

    UUID stayRequest(int guests) throws Exception {
        String body = mvc.perform(get("/api/search").param("lat", String.valueOf(hotel.lat()))
                        .param("lng", String.valueOf(hotel.lng())).param("arrive", "2026-11-11T23:30")
                        .param("leave", "2026-11-12T09:00").param("guests", String.valueOf(guests)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("stayRequestId").asText());
    }

    ResultActions create(UUID stayRequestId, UUID roomTypeId) throws Exception {
        return mvc.perform(post("/api/negotiations").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("stayRequestId", stayRequestId, "roomTypeId", roomTypeId))));
    }

    String open() throws Exception {
        String body = create(stayRequest(2), room.id()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asText();
    }

    ResultActions send(String id, String text) throws Exception {
        return mvc.perform(post("/api/negotiations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": " + PromptText.quote(text) + "}"));
    }

    List<Map<String, Object>> turns(String id) {
        return jdbc.sql("""
                        SELECT seq, role, text, intent::text AS intent, decision::text AS decision, validator_result,
                               latency_ms
                        FROM negotiation_turn WHERE session_id = :id ORDER BY seq""")
                .param("id", UUID.fromString(id)).query().listOfRows();
    }

    Map<String, Object> sessionRow(String id) {
        return jdbc.sql("SELECT * FROM negotiation_session WHERE id = :id").param("id", UUID.fromString(id))
                .query().singleRow();
    }

    @Test
    void createsSessionWithOpeningOfferAndFirstBotMessage() throws Exception {
        String body = create(stayRequest(2), room.id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.currentOffer").value(1000))
                .andExpect(jsonPath("$.round").value(0))
                .andExpect(jsonPath("$.maxRounds").value(4))
                .andExpect(jsonPath("$.hotel.name").value("Persist Lodge"))
                .andExpect(jsonPath("$.roomType.id").value(room.id().toString()))
                .andExpect(jsonPath("$.messages[0].role").value("BOT"))
                .andExpect(jsonPath("$.messages[0].text").value("The room is ₹1,000 for tonight."))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("700", "floor");

        String id = json.readTree(body).path("id").asText();
        Map<String, Object> row = sessionRow(id);
        assertThat(row).containsEntry("status", "OPEN").containsEntry("current_offer", 1000)
                .containsEntry("floor_price", 700).containsEntry("list_price", 1400)
                .containsEntry("slot_type", "LATE_NIGHT").containsEntry("round", 0);
        List<Map<String, Object>> turns = turns(id);
        assertThat(turns).hasSize(1);
        assertThat(turns.getFirst()).containsEntry("seq", 0).containsEntry("role", "BOT");
        assertThat(json.readTree((String) turns.getFirst().get("decision")).path("reasonCode").asText())
                .isEqualTo("LATE_HOUR");
        assertThat((String) turns.getFirst().get("validator_result")).startsWith("TEMPLATE: llm unavailable");
    }

    @Test
    void everyMessagePersistsBothTurnsAndTheNewState() throws Exception {
        String id = open();

        send(id, "500 mein de do bhai").andExpect(status().isOk()).andExpect(jsonPath("$.offer").value(900));

        assertThat(sessionRow(id)).containsEntry("current_offer", 900).containsEntry("round", 1);
        List<Map<String, Object>> turns = turns(id);
        assertThat(turns).extracting(t -> t.get("role")).containsExactly("BOT", "TRAVELLER", "BOT");
        Map<String, Object> traveller = turns.get(1);
        assertThat(traveller).containsEntry("text", "500 mein de do bhai");
        JsonNode intent = json.readTree((String) traveller.get("intent"));
        assertThat(intent.path("type").asText()).isEqualTo("COUNTER");
        assertThat(intent.path("amount").asLong()).isEqualTo(500);
        assertThat(intent.path("register").asText()).isEqualTo("hinglish");
        assertThat(traveller.get("decision")).isNull();

        Map<String, Object> bot = turns.get(2);
        JsonNode decision = json.readTree((String) bot.get("decision"));
        assertThat(decision.path("action").asText()).isEqualTo("OFFER");
        assertThat(decision.path("amount").asLong()).isEqualTo(900);
        assertThat(bot.get("latency_ms")).isNotNull();
        assertThat((String) bot.get("validator_result")).startsWith("TEMPLATE");
        assertThat((String) bot.get("text")).contains("₹900");
    }

    @Test
    void questionsArePersistedWithoutADecision() throws Exception {
        String id = open();

        send(id, "heater hai?").andExpect(jsonPath("$.intent").value("QUESTION")).andExpect(jsonPath("$.offer").value(1000));

        Map<String, Object> bot = turns(id).getLast();
        assertThat(bot.get("decision")).isNull();
        assertThat(sessionRow(id)).containsEntry("round", 0);
    }

    @Test
    void stateIsRebuiltFromTheDatabase() throws Exception {
        String id = open();
        send(id, "500");

        JdbcConversationRepository fresh = new JdbcConversationRepository(jdbc, engine);
        var snapshot = tx.execute(s -> fresh.lockAndLoad(id).orElseThrow().session().snapshot());

        assertThat(snapshot.currentOffer()).isEqualTo(900);
        assertThat(snapshot.round()).isEqualTo(1);
        assertThat(snapshot.policy().floorPrice()).isEqualTo(700);
        List<com.latestay.conversation.ChatMessage> transcript =
                tx.execute(s -> fresh.lockAndLoad(id).orElseThrow().transcript());
        assertThat(transcript).hasSize(3);
    }

    @Test
    void sessionKeepsItsFloorWhenTheHotelEditsTheRule() throws Exception {
        String id = open();
        send(id, "500").andExpect(jsonPath("$.offer").value(900));

        FloorRule rule = floorRules.forRoomType(room.id()).getFirst();
        floorRules.update(rule.id(), new FloorRule(null, room.id(), rule.slotType(), rule.weekdays(), null, null, 900, 0));

        // With the snapshot floor (700): gap 200, 25% = 50 -> 850. A live floor of 900 would have ended at 900.
        send(id, "700").andExpect(jsonPath("$.offer").value(850)).andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void agreedSessionIsStoredAndClosed() throws Exception {
        String id = open();
        send(id, "ok done").andExpect(jsonPath("$.status").value("AGREED"));

        assertThat(sessionRow(id)).containsEntry("status", SessionStatus.AGREED.name()).containsEntry("agreed_price", 1000);
        send(id, "500").andExpect(status().isConflict()).andExpect(jsonPath("$.sessionStatus").value("AGREED"));
        assertThat(turns(id)).hasSize(3);
    }

    @Test
    void viewReloadsTheChatWithoutTheFloor() throws Exception {
        String id = open();
        send(id, "500");

        String body = mvc.perform(get("/api/negotiations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.currentOffer").value(900))
                .andExpect(jsonPath("$.hotel.slug").value(hotel.slug()))
                .andExpect(jsonPath("$.hotel.photo").value(hotel.photos().getFirst()))
                .andExpect(jsonPath("$.roomType.id").value(room.id().toString()))
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[1].role").value("TRAVELLER"))
                .andExpect(jsonPath("$.messages[1].text").value("500"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("700", "floor");
        mvc.perform(get("/api/negotiations/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void acceptAgreesAtTheCurrentOfferWithoutParsingText() throws Exception {
        String id = open();
        send(id, "500").andExpect(jsonPath("$.offer").value(900));

        mvc.perform(post("/api/negotiations/{id}/accept", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGREED"))
                .andExpect(jsonPath("$.offer").value(900))
                .andExpect(jsonPath("$.reasonCode").value("ACCEPTED"))
                .andExpect(jsonPath("$.intent").value("ACCEPT"));

        assertThat(sessionRow(id)).containsEntry("agreed_price", 900);
        Map<String, Object> traveller = turns(id).get(3);
        assertThat(traveller).containsEntry("text", "Accept ₹900");
        assertThat(json.readTree((String) traveller.get("intent")).path("type").asText()).isEqualTo("ACCEPT");
        mvc.perform(post("/api/negotiations/{id}/accept", id)).andExpect(status().isConflict());
        mvc.perform(get("/api/negotiations/{id}", id)).andExpect(jsonPath("$.agreedPrice").value(900));
    }

    @Test
    void hotelPageShowsTonightsPricesForTheStay() throws Exception {
        UUID stay = stayRequest(2);

        String body = mvc.perform(get("/api/hotels/{slug}", hotel.slug()).param("stayRequestId", stay.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Persist Lodge"))
                .andExpect(jsonPath("$.policies.coupleFriendly").value(true))
                .andExpect(jsonPath("$.stay.nightOf").value("2026-11-11"))
                .andExpect(jsonPath("$.stay.slotType").value("LATE_NIGHT"))
                .andExpect(jsonPath("$.stay.arriveFrom").value("21:00:00"))
                .andExpect(jsonPath("$.stay.leaveBy").value("10:00:00"))
                .andExpect(jsonPath("$.stay.roomsFree").value(2))
                .andExpect(jsonPath("$.stay.distanceKm").value(0.0))
                .andExpect(jsonPath("$.stay.fromPrice").value(1000))
                .andExpect(jsonPath("$.stay.roomTypes[0].id").value(room.id().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("700", "floor");

        mvc.perform(get("/api/hotels/{slug}", hotel.slug())).andExpect(status().isOk())
                .andExpect(jsonPath("$.stay").doesNotExist());
        mvc.perform(get("/api/hotels/no-such-hotel")).andExpect(status().isNotFound());
        mvc.perform(get("/api/hotels/{slug}", hotel.slug()).param("stayRequestId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void notFoundCases() throws Exception {
        create(UUID.randomUUID(), room.id()).andExpect(status().isNotFound());
        create(stayRequest(2), UUID.randomUUID()).andExpect(status().isNotFound());
        send(UUID.randomUUID().toString(), "hi").andExpect(status().isNotFound());
        send("not-a-uuid", "hi").andExpect(status().isNotFound());
        mvc.perform(post("/api/negotiations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conflictWhenRoomTooSmallOrNoRoomsLeft() throws Exception {
        UUID threeGuests = stayRequest(3);
        create(threeGuests, room.id()).andExpect(status().isConflict());

        UUID request = stayRequest(2);
        fixtures.roomsFree(hotel, NIGHT, 0);
        create(request, room.id()).andExpect(status().isConflict());
    }
}
