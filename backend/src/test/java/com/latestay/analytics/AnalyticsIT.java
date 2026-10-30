package com.latestay.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.TestBookings;
import com.latestay.stay.StayRequestRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** UTM capture, the events endpoint, and that no event ever carries a phone number, a name or a code. */
class AnalyticsIT extends BookingItSupport {

    static final double LAT = 37.0;
    static final String AID = "aid_test_8f3k2m9q";

    @Autowired
    StayRequestRepository stayRequests;
    @Autowired
    Analytics analytics;

    Map<String, Object> stayRow(UUID id) {
        return jdbc.sql("SELECT utm_source, utm_medium, utm_campaign, analytics_id, town FROM stay_request WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    // UTM

    @Test
    void firstTouchUtmTagsGoFromTheSearchToTheStayRequestAndTheBooking() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        String body = mvc.perform(get("/api/search").param("lat", String.valueOf(agreed.hotel().lat()))
                        .param("lng", String.valueOf(agreed.hotel().lng())).param("arrive", "2026-11-11T23:30")
                        .param("leave", "2026-11-12T09:00").param("guests", "2")
                        .param("utm_source", "Instagram").param("utm_medium", "reel")
                        .param("utm_campaign", "manali-night-01").param("aid", AID))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID stay = UUID.fromString(body.replaceAll(".*\"stayRequestId\":\"([0-9a-f-]{36})\".*", "$1"));
        assertThat(stayRow(stay)).containsEntry("utm_source", "instagram").containsEntry("utm_medium", "reel")
                .containsEntry("utm_campaign", "manali-night-01").containsEntry("analytics_id", AID)
                .containsEntry("town", "Testpur");

        // The booking made from this stay keeps them.
        jdbc.sql("UPDATE stay_request SET utm_source = 'instagram', utm_medium = 'reel', utm_campaign = 'manali-night-01' "
                + "WHERE id = :id").param("id", agreed.stay().id()).update();
        BookingCreated created = fixtures.book(agreed);
        assertThat(jdbc.sql("SELECT utm_source, utm_medium, utm_campaign FROM booking WHERE id = :id")
                .param("id", created.bookingId()).query().singleRow())
                .containsEntry("utm_source", "instagram").containsEntry("utm_campaign", "manali-night-01");
    }

    @Test
    void theServerRenderedSearchIsTaggedByTheBrowserOnce() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        UUID stay = agreed.stay().id();
        jdbc.sql("UPDATE stay_request SET town = 'Testpur' WHERE id = :id").param("id", stay).update();
        String tags = "{\"utmSource\": \"youtube\", \"utmMedium\": \"short\", \"analyticsId\": \"" + AID + "\"}";
        mvc.perform(post("/api/stay-requests/{id}/attribution", stay).contentType(MediaType.APPLICATION_JSON).content(tags))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/stay-requests/{id}/attribution", stay).contentType(MediaType.APPLICATION_JSON)
                .content(tags.replace("youtube", "spam"))).andExpect(status().isNoContent());
        assertThat(stayRow(stay)).containsEntry("utm_source", "youtube").containsEntry("analytics_id", AID);

        // Chat events find their stay (and town) through the negotiation.
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "counter_sent", "distinctId": "%s", "negotiationId": "%s", "properties": {"round": 1}}"""
                        .formatted(AID, agreed.sessionId())))
                .andExpect(status().isAccepted());
        assertThat(jdbc.sql("SELECT town FROM analytics_event WHERE stay_request_id = :s AND name = 'counter_sent'")
                .param("s", stay).query(String.class).single()).isEqualTo("Testpur");
    }

    @Test
    void tagsThatAreNotTagsAreDropped() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        String body = mvc.perform(get("/api/search").param("lat", String.valueOf(agreed.hotel().lat()))
                        .param("lng", String.valueOf(agreed.hotel().lng())).param("arrive", "2026-11-11T23:30")
                        .param("leave", "2026-11-12T09:00").param("guests", "2")
                        .param("utm_source", "+919876543210").param("utm_medium", "Asha Verma")
                        .param("utm_campaign", "x".repeat(200)).param("aid", "short"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID stay = UUID.fromString(body.replaceAll(".*\"stayRequestId\":\"([0-9a-f-]{36})\".*", "$1"));
        assertThat(stayRow(stay)).containsEntry("utm_source", null).containsEntry("utm_medium", null)
                .containsEntry("utm_campaign", null).containsEntry("analytics_id", null);
    }

    // The events endpoint

    @Test
    void onlyBrowserEventsAreAcceptedAndUnknownPropertiesAreDropped() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "room_now_tapped", "distinctId": "%s", "utmSource": "instagram",
                         "properties": {"located": true, "phone": "+919876543210", "name": "Asha Verma"}}""".formatted(AID)))
                .andExpect(status().isAccepted());
        Map<String, Object> row = jdbc.sql("""
                        SELECT properties::text AS props, utm_source FROM analytics_event
                        WHERE distinct_id = :d AND name = 'room_now_tapped' ORDER BY id DESC LIMIT 1""")
                .param("d", AID).query().singleRow();
        assertThat(row).containsEntry("props", "{\"located\": true}").containsEntry("utm_source", "instagram");

        // The backend alone vouches for payments and the rest.
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"payment_succeeded\", \"distinctId\": \"" + AID + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"made_up\", \"distinctId\": \"" + AID + "\"}"))
                .andExpect(status().isBadRequest());
        // A phone number as the distinct id is refused outright.
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"code_sent\", \"distinctId\": \"+919876543210\"}"))
                .andExpect(status().isBadRequest());
    }

    // Privacy across a whole run of bookings

    @Test
    void noEventContainsAPhoneNumberANameOrACode() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 9);
        jdbc.sql("UPDATE stay_request SET analytics_id = :a, utm_source = 'instagram' WHERE id = :id")
                .param("a", AID).param("id", agreed.stay().id()).update();
        String hotel = agreed.hotel().slug();

        // Browser events, including attempts to slip personal data in.
        for (var e : List.of(
                Map.<String, Object>of("hotel", hotel, "round", 2),
                Map.<String, Object>of("hotel", "Asha Verma", "round", 9876543210L),
                Map.<String, Object>of("hotel", "4821", "price_band", "1300"),
                Map.<String, Object>of("hotel", "+91 98765 43210"))) {
            analytics.record(AnalyticsEventName.COUNTER_SENT, AID, agreed.stay().id(), null, e);
        }
        // Backend events: paid, confirmed, checked in; cancelled; turned away; rescued.
        BookingCreated stayed = fixtures.book(agreed);
        fixtures.pay(stayed);
        bookings.confirm(stayed.bookingId());
        bookings.checkInByOwner(stayed.bookingId());
        BookingCreated cancelled = fixtures.book(fixtures.agreedAnother(agreed));
        fixtures.pay(cancelled);
        bookings.cancel(cancelled.bookingId());
        BookingCreated away = fixtures.book(fixtures.agreedAnother(agreed));
        fixtures.pay(away);
        bookings.confirm(away.bookingId());
        clock.set(java.time.Instant.parse("2026-11-11T18:05:00Z"));
        bookings.turnedAway(away.bookingId());
        bookings.rescue(away.bookingId());

        // Every stay request at this room (the other bookings come from other travellers' searches).
        List<String> codes = jdbc.sql("SELECT code FROM booking WHERE room_type_id = :r")
                .param("r", agreed.room().id()).query(String.class).list();
        assertThat(codes).hasSize(3);
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, name, distinct_id, town, utm_source, properties::text AS properties, occurred_at
                        FROM analytics_event WHERE stay_request_id IN (
                            SELECT stay_request_id FROM negotiation_session WHERE room_type_id = :r)
                        ORDER BY id""")
                .param("r", agreed.room().id()).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("name")).contains("counter_sent", "payment_succeeded",
                "hotel_confirmed", "checked_in", "cancelled", "turned_away", "rescued");

        String recorded = rows.toString();
        // And exactly what PostHog would receive.
        String sent = PostHogForwarder.batch("phc_test", rows.stream().map(r -> new PostHogForwarder.Row(
                (Long) r.get("id"), (String) r.get("name"), (String) r.get("distinct_id"), (String) r.get("town"),
                (String) r.get("utm_source"), (String) r.get("properties"),
                ((java.sql.Timestamp) r.get("occurred_at")).toInstant().atOffset(java.time.ZoneOffset.UTC))).toList());
        for (String payload : List.of(recorded, sent)) {
            // Ids and timestamps aside, nothing with 4 or more digits in a row is left: no phone, no code.
            String content = payload.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>")
                    .replaceAll("\\d{4}-\\d{2}-\\d{2}[T ][\\d:.]+Z?", "<time>");
            assertThat(content).doesNotContain("Asha").doesNotContain("Verma").doesNotContain("+91")
                    .doesNotContainPattern("\\d{4,}");
            for (String code : codes) {
                assertThat(content).doesNotContain(code);
            }
        }
        assertThat(sent).contains("\"$ip\":null").contains("\"$process_person_profile\":false")
                .contains("\"$geoip_disable\":true");
        // The browser's random id, or a stand-in made from the stay request's id: never anything personal.
        assertThat(rows).allSatisfy(r -> assertThat((String) r.get("distinct_id")).matches(
                AID + "|stay:[0-9a-f-]{36}"));
    }

    @Test
    void theFunnelCountsVisitorsPerStepWithDropOffFilters() throws Exception {
        // Letters only: a random hex suffix could hold 4 digits in a row, which the privacy filter drops.
        var random = new java.util.Random();
        String source = "funnel-" + random.ints(8, 'a', 'z' + 1)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
        // Three visitors search, two open a hotel, one starts haggling; one more from another source.
        for (String visitor : List.of("visitor_aaaaaaaa", "visitor_bbbbbbbb", "visitor_cccccccc")) {
            analytics.record(AnalyticsEventName.SEARCH_STARTED, visitor, null, source, Map.of());
            analytics.record(AnalyticsEventName.SEARCH_STARTED, visitor, null, source, Map.of()); // counted once
        }
        analytics.record(AnalyticsEventName.HOTEL_OPENED, "visitor_aaaaaaaa", null, source, Map.of());
        analytics.record(AnalyticsEventName.HOTEL_OPENED, "visitor_bbbbbbbb", null, source, Map.of());
        analytics.record(AnalyticsEventName.HAGGLE_STARTED, "visitor_aaaaaaaa", null, source, Map.of());
        analytics.record(AnalyticsEventName.SEARCH_STARTED, "visitor_dddddddd", null, "elsewhere", Map.of());

        mvc.perform(get("/api/admin/funnel").param("days", "7").param("utmSource", source)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.steps[0].name")
                        .value("search_started"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.steps[0].visitors")
                        .value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.steps[2].visitors")
                        .value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.steps[3].visitors")
                        .value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources")
                        .value(org.hamcrest.Matchers.hasItem(source)));
        mvc.perform(get("/api/admin/funnel").param("days", "14").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void priceBandsHaveNoFourDigitRuns() {
        assertThat(AnalyticsSanitizer.priceBand(1300)).isEqualTo("1k-1.5k");
        assertThat(AnalyticsSanitizer.priceBand(450)).isEqualTo("0k-0.5k");
        assertThat(AnalyticsSanitizer.isTag(AnalyticsSanitizer.priceBand(12_345))).isTrue();
        assertThat(AnalyticsSanitizer.isTag("4821")).isFalse();
        assertThat(AnalyticsSanitizer.isTag("Asha Verma")).isFalse();
    }
}
