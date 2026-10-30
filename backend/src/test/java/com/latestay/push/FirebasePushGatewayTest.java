package com.latestay.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.Test;

class FirebasePushGatewayTest {

    @Test
    void theMessageCarriesOnlyThePayloadOnTheBookingsChannel() throws Exception {
        String id = "0b6f3c5e-8c1a-4a57-9d55-1d0f6a2c3b4e";
        Message message = FirebasePushGateway.message("token-123",
                PushPayload.of(id, "Deodar Nest confirmed. Your code is ready.", "/pass/" + id));
        String json = GsonFactory.getDefaultInstance().toString(message);
        var tree = new ObjectMapper().readTree(json);
        assertThat(tree.path("notification").path("title").asText()).isEqualTo("LateStay");
        assertThat(tree.path("notification").path("body").asText()).isEqualTo("Deodar Nest confirmed. Your code is ready.");
        assertThat(tree.path("data").path("path").asText()).isEqualTo("/pass/" + id);
        assertThat(tree.path("android").path("notification").path("channel_id").asText()).isEqualTo("bookings");
        assertThat(tree.path("android").path("priority").asText()).isEqualTo("high");
        assertThat(tree.path("apns").path("payload").path("aps").path("sound").asText()).isEqualTo("default");
    }
}
