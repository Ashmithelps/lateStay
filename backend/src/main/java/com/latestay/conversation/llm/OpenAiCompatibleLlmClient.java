package com.latestay.conversation.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Any server that speaks the OpenAI Chat Completions API. */
public class OpenAiCompatibleLlmClient implements LlmClient {

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final RestClient http;
    private final String model;
    private final String apiKey;

    public OpenAiCompatibleLlmClient(RestClient http, String model, String apiKey) {
        this.http = http;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.system()));
        request.messages().forEach(m -> messages.add(Map.of("role", m.role().name().toLowerCase(), "content", m.content())));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        body.put("messages", messages);
        if (request.wantsJson()) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of("name", request.schemaName(), "strict", true, "schema", request.jsonSchema())));
        }

        JsonNode response;
        try {
            response = http.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw HttpErrors.unavailable("OpenAI-compatible", e);
        }
        if (response == null) {
            throw new LlmUnavailableException("OpenAI-compatible server returned an empty body");
        }

        JsonNode choice = response.path("choices").path(0);
        String finishReason = choice.path("finish_reason").asText();
        JsonNode message = choice.path("message");
        if (!"stop".equals(finishReason) || !message.path("refusal").isMissingNode() && !message.path("refusal").isNull()) {
            throw new LlmUnavailableException("OpenAI-compatible server finished with " + finishReason);
        }
        String content = message.path("content").asText("");
        if (content.isBlank()) {
            throw new LlmUnavailableException("OpenAI-compatible server returned no text");
        }
        JsonNode usage = response.path("usage");
        return new LlmResponse(content, usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
    }

    @Override
    public String model() {
        return model;
    }
}
