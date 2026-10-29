package com.latestay.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

/** Production JSON logs run every string field through Pii.scrub, stack traces included. */
class PiiLogCustomizerTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void everyStringFieldIsScrubbed() {
        JsonWriter<Map<String, String>> writer = JsonWriter.of(members -> {
            members.add("message", event -> event.get("message"));
            members.add("stack_trace", event -> event.get("stack_trace"));
            members.add("logger_name", event -> event.get("logger"));
            new PiiLogCustomizer().customize((JsonWriter.Members) members);
        });
        String line = writer.writeToString(Map.of(
                "message", "Payment failed for +91 98123 45678",
                "stack_trace", "org.postgresql.util.PSQLException: Failing row contains (Asha, +919812345678)",
                "logger", "com.latestay.booking.BookingService"));

        assertThat(line).doesNotContain("98123 45678").doesNotContain("9812345678")
                .contains("+91•••••5678").contains("com.latestay.booking.BookingService");
    }
}
