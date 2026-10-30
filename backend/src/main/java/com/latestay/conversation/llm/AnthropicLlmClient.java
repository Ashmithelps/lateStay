package com.latestay.conversation.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Claude Messages API over HTTP. */
public class AnthropicLlmClient implements LlmClient {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_MODEL = "claude-haiku-5-5";

    private final RestClient http;
    private final String model;
    private final String apiKey;

    public AnthropicLlmClient(RestClient http, String model, String apiKey) {
        this.http = http;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Map<String, Object> outputConfig = new LinkedHashMap<>();
        // Short classification and phrasing: keep thinking light for latency.
        outputConfig.put("effort", "low");
        if (request.wantsJson()) {
            outputConfig.put("format", Map.of("type", "json_schema", "schema", request.jsonSchema()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        body.put("system", request.system());
        body.put("messages", request.messages().stream()
                .map(m -> Map.of("role", m.role().name().toLowerCase(), "content", m.content()))
                .toList());
        body.put("output_config", outputConfig);

        JsonNode response;
        try {
            response = http.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw HttpErrors.unavailable("Anthropic", e);
        }
        if (response == null) {
            throw new LlmUnavailableException("Anthropic returned an empty body");
        }

        String stopReason = response.path("stop_reason").asText();
        if (!List.of("end_turn", "stop_sequence").contains(stopReason)) {
            throw new LlmUnavailableException("Anthropic stopped with " + stopReason);
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        if (text.isEmpty()) {
            throw new LlmUnavailableException("Anthropic returned no text");
        }
        JsonNode usage = response.path("usage");
        return new LlmResponse(text.toString(), usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0));
    }

    @Override
    public String model() {
        return model;
    }
}
