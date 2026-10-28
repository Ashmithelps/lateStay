package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** POST {base}/{version}/{phone number id}/messages with the access token as a bearer token. */
class GraphWhatsAppClient implements WhatsAppClient {

    private final WhatsAppProperties properties;
    private final RestClient http;

    GraphWhatsAppClient(WhatsAppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.baseUrl(properties.baseUrl())
                .defaultHeaders(h -> h.setBearerAuth(properties.accessToken()))
                .build();
    }

    @Override
    public boolean canSend() {
        return properties.canSend();
    }

    @Override
    public String send(JsonNode message) {
        try {
            JsonNode response = http.post()
                    .uri("/{version}/{phone}/messages", properties.graphVersion(), properties.phoneNumberId())
                    .contentType(MediaType.APPLICATION_JSON).body(message)
                    .retrieve().body(JsonNode.class);
            String id = response == null ? "" : response.path("messages").path(0).path("id").asText();
            if (id.isEmpty()) {
                throw new WhatsAppSendException("Graph API returned no message id: " + response);
            }
            return id;
        } catch (RestClientResponseException e) {
            throw new WhatsAppSendException("Graph API " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new WhatsAppSendException("Graph API unreachable: " + e.getMessage(), e);
        }
    }
}
