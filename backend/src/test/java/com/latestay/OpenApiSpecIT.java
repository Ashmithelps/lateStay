package com.latestay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exports the public API contract to web/openapi.json (the web app generates its TypeScript types from it) and
 * checks it never exposes the floor price or the admin and dev endpoints.
 */
@SpringBootTest(properties = "springdoc.writer-with-order-by-keys=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiSpecIT {

    static final Path WEB_SPEC = Path.of("..", "web", "openapi.json");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void publicSpecIsWrittenForTheWebAppAndHidesTheFloor() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode spec = json.readTree(body);

        assertThat(spec.path("paths").fieldNames()).toIterable().contains(
                "/api/search", "/api/hotels/{slug}", "/api/negotiations", "/api/negotiations/{id}",
                "/api/negotiations/{id}/messages", "/api/negotiations/{id}/accept", "/api/bookings",
                "/api/bookings/{id}", "/api/bookings/{id}/verify", "/api/bookings/{id}/cancel", "/api/negotiations/{sessionId}/booking-preview",
                "/api/bookings/{id}/arrived", "/api/bookings/{id}/turned-away", "/api/phone-verifications",
                "/api/phone-verifications/{id}/check", "/api/phone-verifications/status");
        // Razorpay's webhook is public but not part of the web app's contract.
        assertThat(spec.path("paths").has("/api/webhooks/razorpay")).isFalse();
        assertThat(spec.path("paths").fieldNames()).toIterable()
                .noneMatch(p -> p.startsWith("/api/admin") || p.startsWith("/api/dev") || p.startsWith("/api/owner"));
        // The FLOOR_REACHED reason code names a situation, not a value; anything else mentioning the floor fails.
        assertThat(body.toLowerCase().replace("floor_reached", "")).doesNotContain("floor");

        // Stable output: drop the server URL (it carries a random port) and pretty-print.
        ((com.fasterxml.jackson.databind.node.ObjectNode) spec).remove("servers");
        if (Files.isDirectory(WEB_SPEC.getParent())) {
            Files.writeString(WEB_SPEC, json.copy().enable(SerializationFeature.INDENT_OUTPUT)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(spec) + "\n");
        }
    }
}
