package com.latestay.conversation.llm;

/**
 * LLM spend for one traveller message.
 *
 * @param calls requests attempted, including ones that failed
 */
public record LlmUsage(int calls, long inputTokens, long outputTokens) {

    public static final LlmUsage NONE = new LlmUsage(0, 0, 0);
}
