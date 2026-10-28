package com.latestay.conversation.llm;

import java.util.List;
import java.util.Map;

/**
 * One completion request.
 *
 * @param jsonSchema when non-null, the response must be a JSON object matching this schema
 * @param schemaName short identifier for the schema (some providers require one)
 */
public record LlmRequest(String system, List<LlmMessage> messages, Map<String, Object> jsonSchema, String schemaName,
        int maxTokens) {

    public static LlmRequest text(String system, List<LlmMessage> messages, int maxTokens) {
        return new LlmRequest(system, messages, null, null, maxTokens);
    }

    public static LlmRequest json(String system, List<LlmMessage> messages, String schemaName,
            Map<String, Object> jsonSchema, int maxTokens) {
        return new LlmRequest(system, messages, jsonSchema, schemaName, maxTokens);
    }

    public boolean wantsJson() {
        return jsonSchema != null;
    }
}
