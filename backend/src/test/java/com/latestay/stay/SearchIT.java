package com.latestay.stay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.TestHotels;
import com.latestay.TestcontainersConfiguration;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.negotiation.SlotType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestHotels.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIT {

    // A made-up area well away from the local seed data (Manali, Kasol) and other tests.
    static final double LAT = 31.10;
    static final double LNG = 78.10;
    static final LocalDate NIGHT = LocalDate.of(2026, 11, 4);
    static final LocalDate NEXT_NIGHT = NIGHT.plusDays(1);
    static final double KM = TestHotels.KM;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    TestHotels fixtures;
    @Autowired
    NegotiationEngine engine;
    @Autowired
    JdbcClient jdbc;

    Hotel near;
    Hotel mid;
    Hotel full;
    Hotel far;
    Hotel otherTown;
    Hotel tiny;
    Hotel inactive;
    Hotel lateOnly;
    Hotel samePointExpensive;
    Hotel samePointCheap;

    @BeforeAll
    void createHotels() {
        HotelPolicies couple = new HotelPolicies(true, true, "Desk open all night");
        HotelPolicies plain = new HotelPolicies(false, false, null);
        near = fixtures.hotel("Near Heater", LAT + 0.5 * KM, LNG, List.of("heater", "parking"), couple, true);
        fixtures.room(near, 2000, 2, 1000, SlotType.LATE_NIGHT, SlotType.EARLY_MORNING);
        fixtures.roomsFree(near, NIGHT, 3);

        mid = fixtures.hotel("Mid Parking", LAT + 3 * KM, LNG, List.of("parking"), plain, true);
        fixtures.room(mid, 1500, 4, 900, SlotType.LATE_NIGHT, SlotType.EARLY_MORNING);
        fixtures.roomsFree(mid, NIGHT, 2);
        fixtures.roomsFree(mid, NEXT_NIGHT, 2);

        full = fixtures.hotel("Full Up", LAT + 1 * KM, LNG, List.of("heater"), couple, true);
        fixtures.room(full, 1800, 2, 900, SlotType.LATE_NIGHT);
        fixtures.roomsFree(full, NIGHT, 0);

        far = fixtures.hotel("Too Far", LAT + 15 * KM, LNG, List.of("heater"), couple, true);
        fixtures.room(far, 1200, 2, 600, SlotType.LATE_NIGHT);
        fixtures.roomsFree(far, NIGHT, 5);

        otherTown = fixtures.hotel("Other Town", LAT + 30 * KM, LNG, List.of("heater"), couple, true);
        fixtures.room(otherTown, 1200, 2, 600, SlotType.LATE_NIGHT);
        fixtures.roomsFree(otherTown, NIGHT, 5);

        tiny = fixtures.hotel("Single Rooms", LAT + 2 * KM, LNG, List.of("heater"), plain, true);
        fixtures.room(tiny, 1000, 1, 500, SlotType.LATE_NIGHT);
        fixtures.roomsFree(tiny, NIGHT, 4);

        inactive = fixtures.hotel("Closed Down", LAT + 0.8 * KM, LNG, List.of("heater"), couple, false);
        fixtures.room(inactive, 1500, 2, 700, SlotType.LATE_NIGHT);
        fixtures.roomsFree(inactive, NIGHT, 4);

        lateOnly = fixtures.hotel("Late Only", LAT + 1.5 * KM, LNG, List.of(), plain, true);
        fixtures.room(lateOnly, 1600, 2, 800, SlotType.LATE_NIGHT);
        fixtures.roomsFree(lateOnly, NIGHT, 1);
        fixtures.roomsFree(lateOnly, NEXT_NIGHT, 1);

        samePointExpensive = fixtures.hotel("Same Point Expensive", LAT + 5 * KM, LNG, List.of(), plain, true);
        fixtures.room(samePointExpensive, 3000, 2, 1500, SlotType.LATE_NIGHT);
        fixtures.roomsFree(samePointExpensive, NIGHT, 1);
        samePointCheap = fixtures.hotel("Same Point Cheap", LAT + 5 * KM, LNG, List.of(), plain, true);
        fixtures.room(samePointCheap, 1200, 2, 600, SlotType.LATE_NIGHT);
        fixtures.roomsFree(samePointCheap, NIGHT, 1);
    }

    JsonNode search(double lat, double lng, String arrive, String leave, int guests, String filters) throws Exception {
        var request = get("/api/search").param("lat", String.valueOf(lat)).param("lng", String.valueOf(lng))
                .param("arrive", arrive).param("leave", leave).param("guests", String.valueOf(guests));
        if (filters != null) {
            request.param("filters", filters);
        }
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    JsonNode lateSearch(int guests, String filters) throws Exception {
        return search(LAT, LNG, "2026-11-04T23:30", "2026-11-05T09:00", guests, filters);
    }

    static List<String> names(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.path("results").forEach(r -> names.add(r.path("name").asText()));
        return names;
    }

    @Test
    void findsHotelsWithin10kmRankedByDistanceBandThenReliabilityThenPrice() throws Exception {
        JsonNode response = lateSearch(2, null);

        // Under 1 km; 1-3 km (Mid Parking at just under 3 km, Late Only, then Single Rooms' dearer pair of rooms);
        // 3 km and more. Every hotel here is new (score 80), so price decides within a band.
        assertThat(names(response)).containsExactly("Near Heater", "Mid Parking", "Late Only", "Single Rooms",
                "Same Point Cheap", "Same Point Expensive");
        assertThat(response.path("nightOf").asText()).isEqualTo("2026-11-04");
        assertThat(response.path("slotType").asText()).isEqualTo("LATE_NIGHT");
        JsonNode first = response.path("results").get(0);
        assertThat(first.path("distanceKm").asDouble()).isBetween(0.45, 0.55);
        assertThat(first.path("roomsFree").asInt()).isEqualTo(3);
        assertThat(first.path("photos").get(0).asText()).startsWith("/seed/near-heater-");
        assertThat(first.path("slug").asText()).startsWith("near-heater-");
        assertThat(first.path("lat").asDouble()).isEqualTo(near.lat());
        assertThat(first.path("lng").asDouble()).isEqualTo(near.lng());
        assertThat(first.path("amenities").toString()).contains("heater", "parking");
    }

    @Test
    void excludesFarHotelsZeroRoomsInactiveAndRoomsTooSmall() throws Exception {
        assertThat(names(lateSearch(2, null))).doesNotContain("Too Far", "Other Town", "Full Up", "Closed Down");
        assertThat(names(lateSearch(1, null))).contains("Single Rooms");
        // Three guests: Mid Parking's room sleeps 4; Near Heater offers two of its 2-guest rooms.
        assertThat(names(lateSearch(3, null))).containsExactly("Near Heater", "Mid Parking");
        assertThat(lateSearch(3, null).path("results").get(0).path("roomTypes").get(0).path("rooms").asInt())
                .isEqualTo(2);
    }

    @Test
    void otherTownFindsOnlyItsOwnHotels() throws Exception {
        JsonNode response = search(LAT + 30 * KM, LNG, "2026-11-04T23:30", "2026-11-05T09:00", 2, null);

        assertThat(names(response)).containsExactly("Other Town");
    }

    @Test
    void fromPriceIsTheEnginesOpeningPrice() throws Exception {
        JsonNode results = lateSearch(2, null).path("results");
        JsonNode near = results.get(0);
        long expected = engine.openingOffer(PricingPolicy.of(2000, 1000, SlotType.LATE_NIGHT), LocalTime.of(23, 30));

        assertThat(near.path("fromPrice").asLong()).isEqualTo(expected).isEqualTo(1400);
        assertThat(near.path("roomTypes").get(0).path("fromPrice").asLong()).isEqualTo(expected);
        assertThat(results.toString()).doesNotContain("floor");

        JsonNode later = search(LAT, LNG, "2026-11-05T01:00", "2026-11-05T09:00", 2, null).path("results").get(0);
        assertThat(later.path("fromPrice").asLong()).isEqualTo(1200); // 60% after 00:30
    }

    @Test
    void filtersByAmenityAndPolicy() throws Exception {
        assertThat(names(lateSearch(2, "heater"))).containsExactly("Near Heater", "Single Rooms");
        assertThat(names(lateSearch(2, "parking"))).containsExactly("Near Heater", "Mid Parking");
        assertThat(names(lateSearch(2, "Couple-friendly"))).containsExactly("Near Heater");
        assertThat(names(lateSearch(2, "heater,parking,local_id_ok"))).containsExactly("Near Heater");
        assertThat(names(lateSearch(2, "near_bus_stand"))).isEmpty();
    }

    @Test
    void arrivalBeforeSixUsesThePreviousNightsAvailability() throws Exception {
        // 01:30 on the 5th is the night of the 4th: Near Heater has rooms then.
        JsonNode late = search(LAT, LNG, "2026-11-05T01:30", "2026-11-05T10:00", 2, null);
        assertThat(late.path("nightOf").asText()).isEqualTo("2026-11-04");
        assertThat(names(late)).contains("Near Heater");

        // 05:30 is still the night of the 4th, early-morning slot: Late Only has no early-morning rule.
        JsonNode early = search(LAT, LNG, "2026-11-05T05:30", "2026-11-05T12:00", 2, null);
        assertThat(early.path("nightOf").asText()).isEqualTo("2026-11-04");
        assertThat(early.path("slotType").asText()).isEqualTo("EARLY_MORNING");
        assertThat(names(early)).containsExactly("Near Heater", "Mid Parking");

        // 06:30 and 09:00 on the 5th are early-morning arrivals, so still the night of the 4th (the owners'
        // evening answer covers them); the hotel that only reported the 5th stays out.
        for (String arrive : new String[] {"2026-11-05T06:30", "2026-11-05T09:00"}) {
            JsonNode morning = search(LAT, LNG, arrive, "2026-11-05T12:00", 2, null);
            assertThat(morning.path("nightOf").asText()).isEqualTo("2026-11-04");
            assertThat(names(morning)).containsExactly("Near Heater", "Mid Parking");
        }
    }

    @Test
    void recordsTheStayRequest() throws Exception {
        JsonNode response = lateSearch(2, "heater,parking");
        UUID id = UUID.fromString(response.path("stayRequestId").asText());

        String filters = jdbc.sql("SELECT filters::text FROM stay_request WHERE id = :id").param("id", id)
                .query(String.class).single();
        assertThat(filters).isEqualTo("[\"heater\", \"parking\"]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arrive=2026-11-04T18:00&leave=2026-11-05T09:00&guests=2",
            "arrive=2026-11-04T23:30&leave=2026-11-05T11:00&guests=2",
            "arrive=2026-11-04T23:30&leave=2026-11-04T23:00&guests=2",
            "arrive=2026-11-04T23:30&leave=2026-11-05T09:00&guests=0",
            "arrive=2026-11-04T23:30&leave=2026-11-05T09:00&guests=2&filters=jacuzzi",
            "arrive=tonight&leave=2026-11-05T09:00&guests=2",
    })
    void rejectsBadRequests(String query) throws Exception {
        mvc.perform(get("/api/search?lat=" + LAT + "&lng=" + LNG + "&" + query)).andExpect(status().isBadRequest());
    }

    @Test
    void missingLocationIsBadRequest() throws Exception {
        mvc.perform(get("/api/search?arrive=2026-11-04T23:30&leave=2026-11-05T09:00")).andExpect(status().isBadRequest());
    }
}
