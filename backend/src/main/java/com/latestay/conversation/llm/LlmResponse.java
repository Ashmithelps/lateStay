package com.latestay.conversation.llm;

/** The model's text plus the tokens the provider reported (0 when it reports none). */
public record LlmResponse(String text, long inputTokens, long outputTokens) {
}
