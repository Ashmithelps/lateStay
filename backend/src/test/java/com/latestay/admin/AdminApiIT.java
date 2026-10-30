package com.latestay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"latestay.admin.username=ops", "latestay.admin.password=correct horse"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminApiIT {

    static final double LAT = 30.50;
    static final double LNG = 79.50;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.with(httpBasic("ops", "correct horse"));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static String hotelBody(String slug, double lat) {
        return """
                {"name": "Admin Test Inn", "slug": "%s", "town": "Testpur", "address": "1 Ridge Road",
                 "lat": %s, "lng": %s, "whatsappNumber": "+91 90000 12345", "amenities": ["heater", "parking"],
                 "policies": {"coupleFriendly": true, "localIdOk": false, "checkInNotes": "Ring twice"},
                 "totalRooms": 8, "photos": ["/seed/a.png", "/seed/b.png"]}""".formatted(slug, lat, LNG);
    }

    @Test
    void refusesMissingOrWrongCredentials() throws Exception {
        mvc.perform(get("/api/admin/hotels")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/hotels").with(httpBasic("ops", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/hotels").contentType(MediaType.APPLICATION_JSON).content(hotelBody("x", LAT)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointsNeedNoCredentials() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/search?lat=0&lng=0&arrive=2026-11-04T23:30&leave=2026-11-05T09:00"))
                .andExpect(status().isOk());
    }

    @Test
    void hotelRoomRuleAndAvailabilityLifecycle() throws Exception {
        String slug = "admin-test-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode hotel = read(admin(post("/api/admin/hotels"), hotelBody(slug, LAT)).andExpect(status().isCreated()));
        String hotelId = hotel.path("id").asText();
        assertThat(hotel.path("policies").path("coupleFriendly").asBoolean()).isTrue();
        assertThat(hotel.path("photos").size()).isEqualTo(2);
        assertThat(hotel.path("lat").asDouble()).isEqualTo(LAT);

        admin(post("/api/admin/hotels"), hotelBody(slug, LAT)).andExpect(status().isConflict());
        admin(put("/api/admin/hotels/" + hotelId), hotelBody(slug, LAT).replace("Admin Test Inn", "Renamed Inn"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed Inn"));
        admin(get("/api/admin/hotels/" + hotelId), null).andExpect(jsonPath("$.name").value("Renamed Inn"));

        JsonNode room = read(admin(post("/api/admin/hotels/" + hotelId + "/room-types"),
                "{\"name\": \"Double\", \"listPrice\": 1600, \"maxGuests\": 2}").andExpect(status().isCreated()));
        String roomId = room.path("id").asText();

        admin(post("/api/admin/room-types/" + roomId + "/floor-rules"),
                "{\"slotType\": \"LATE_NIGHT\", \"floorPrice\": 2000}").andExpect(status().isBadRequest());
        admin(post("/api/admin/room-types/" + roomId + "/floor-rules"),
                "{\"slotType\": \"LATE_NIGHT\", \"floorPrice\": 800, \"seasonStart\": \"2026-12-20\"}")
                .andExpect(status().isBadRequest());
        JsonNode rule = read(admin(post("/api/admin/room-types/" + roomId + "/floor-rules"),
                "{\"slotType\": \"LATE_NIGHT\", \"floorPrice\": 800}").andExpect(status().isCreated()));
        assertThat(rule.path("weekdays").asInt()).isEqualTo(127);
        admin(put("/api/admin/room-types/" + roomId + "/floor-rules/" + rule.path("id").asText()),
                "{\"slotType\": \"LATE_NIGHT\", \"floorPrice\": 850, \"minHours\": 4}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.floorPrice").value(850));
        admin(put("/api/admin/hotels/" + hotelId + "/room-types/" + roomId),
                "{\"name\": \"Double\", \"listPrice\": 800, \"maxGuests\": 2}").andExpect(status().isBadRequest());

        String search = "/api/search?lat=" + LAT + "&lng=" + LNG + "&arrive=2026-11-04T23:30&leave=2026-11-05T09:00";
        mvc.perform(get(search)).andExpect(jsonPath("$.results.length()").value(0));

        admin(put("/api/admin/hotels/" + hotelId + "/availability/2026-11-04"), "{\"roomsFree\": 2}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("ADMIN"));
        mvc.perform(get(search)).andExpect(jsonPath("$.results[0].name").value("Renamed Inn"))
                .andExpect(jsonPath("$.results[0].roomsFree").value(2))
                .andExpect(jsonPath("$.results[0].fromPrice").value(1150));

        admin(put("/api/admin/hotels/" + hotelId + "/availability/2026-11-04"), "{\"roomsFree\": 0}")
                .andExpect(status().isOk());
        mvc.perform(get(search)).andExpect(jsonPath("$.results.length()").value(0));
        admin(put("/api/admin/hotels/" + hotelId + "/availability/2026-11-04"), "{\"roomsFree\": 1}");

        admin(delete("/api/admin/hotels/" + hotelId), null).andExpect(status().isNoContent());
        mvc.perform(get(search)).andExpect(jsonPath("$.results.length()").value(0));
        admin(get("/api/admin/hotels/" + hotelId), null).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void roomTypeWithANegotiationCannotBeDeleted() throws Exception {
        double lat = LAT + 0.5;
        String slug = "admin-del-" + UUID.randomUUID().toString().substring(0, 8);
        String hotelId = read(admin(post("/api/admin/hotels"), hotelBody(slug, lat))).path("id").asText();
        String roomId = read(admin(post("/api/admin/hotels/" + hotelId + "/room-types"),
                "{\"name\": \"Double\", \"listPrice\": 1600, \"maxGuests\": 2}")).path("id").asText();
        admin(post("/api/admin/room-types/" + roomId + "/floor-rules"), "{\"slotType\": \"LATE_NIGHT\", \"floorPrice\": 800}");
        admin(put("/api/admin/hotels/" + hotelId + "/availability/2026-11-04"), "{\"roomsFree\": 2}");
        JsonNode found = read(mvc.perform(get("/api/search?lat=" + lat + "&lng=" + LNG
                + "&arrive=2026-11-04T23:30&leave=2026-11-05T09:00")));
        mvc.perform(post("/api/negotiations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stayRequestId\": \"" + found.path("stayRequestId").asText()
                                + "\", \"roomTypeId\": \"" + roomId + "\"}"))
                .andExpect(status().isCreated());

        admin(delete("/api/admin/hotels/" + hotelId + "/room-types/" + roomId), null).andExpect(status().isConflict());
    }

    @Test
    void notFound() throws Exception {
        admin(get("/api/admin/hotels/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
        admin(get("/api/admin/hotels/" + UUID.randomUUID() + "/room-types"), null).andExpect(status().isNotFound());
        admin(put("/api/admin/hotels/" + UUID.randomUUID() + "/availability/2026-11-04"), "{\"roomsFree\": 2}")
                .andExpect(status().isNotFound());
    }
}
