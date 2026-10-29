package com.latestay.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.TestcontainersConfiguration;
import com.latestay.whatsapp.WhatsAppWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Rate limits (small ones here), CORS for listed origins only, and phone numbers masked where they're logged.
 */
@SpringBootTest(properties = {
        "latestay.rate-limit.enabled=true",
        "latestay.rate-limit.messages-per-session=3",
        "latestay.rate-limit.messages-per-ip=5",
        "latestay.rate-limit.bookings-per-ip=2",
        "latestay.web-origin=https://latestay.example, https://preview.latestay.example"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class HardeningIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    WhatsAppWebhookService whatsApp;
    @Autowired
    ObjectMapper json;

    ResultActions message(String session, String ip) throws Exception {
        return mvc.perform(post("/api/negotiations/{id}/messages", session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"700 mein de do\"}").with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }));
    }

    @Test
    void messagesAreLimitedPerSession() throws Exception {
        String session = UUID.randomUUID().toString();
        String ip = "203.0.113." + (int) (Math.random() * 200);
        for (int i = 0; i < 3; i++) {
            message(session, ip).andExpect(status().isNotFound()); // unknown session, but counted
        }
        message(session, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.retryAfter").isNumber());
    }

    @Test
    void messagesAreLimitedPerIpAcrossSessions() throws Exception {
        String ip = "198.51.100." + (int) (Math.random() * 200);
        for (int i = 0; i < 5; i++) {
            message(UUID.randomUUID().toString(), ip).andExpect(status().isNotFound());
        }
        message(UUID.randomUUID().toString(), ip).andExpect(status().isTooManyRequests());
        // Another IP is unaffected.
        message(UUID.randomUUID().toString(), "192.0.2.77").andExpect(status().isNotFound());
    }

    @Test
    void bookingCreationIsLimitedPerIp() throws Exception {
        String ip = "192.0.2." + (int) (10 + Math.random() * 50);
        String body = "{\"sessionId\":\"" + UUID.randomUUID() + "\",\"guestName\":\"A\",\"phone\":\"9876543210\","
                + "\"idConsent\":true}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body).with(r -> {
                r.setRemoteAddr(ip);
                return r;
            })).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        })).andExpect(status().isTooManyRequests());
    }

    @Test
    void messagesOver500CharactersAreRejected() throws Exception {
        mvc.perform(post("/api/negotiations/{id}/messages", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "a".repeat(501) + "\"}").with(r -> {
                            r.setRemoteAddr("192.0.2.200");
                            return r;
                        }))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corsAllowsOnlyTheListedOrigins() throws Exception {
        for (String origin : new String[] {"https://latestay.example", "https://preview.latestay.example"}) {
            mvc.perform(options("/api/search").header("Origin", origin).header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
        mvc.perform(options("/api/search").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/search").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void phoneNumbersAreMaskedWhereTheyAreLogged(CapturedOutput output) {
        var message = json.createObjectNode();
        message.put("from", "919876543210").put("id", "wamid.hardening." + UUID.randomUUID()).put("type", "text");
        message.putObject("text").put("body", "rooms 2");
        whatsApp.simulateInbound(message);
        assertThat(output.getOut()).contains("unknown number +91•••••3210 ignored").doesNotContain("919876543210");
    }
}
