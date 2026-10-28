package com.latestay.conversation.llm;

/** Accumulates usage across the LLM calls made for one message. Not thread-safe; one per message. */
public final class LlmUsageMeter {

    private int calls;
    private long inputTokens;
    private long outputTokens;

    /** Runs one call, counting it whether or not it succeeds. */
    public LlmResponse call(LlmClient client, LlmRequest request) {
        calls++;
        LlmResponse response = client.complete(request);
        inputTokens += response.inputTokens();
        outputTokens += response.outputTokens();
        return response;
    }

    public LlmUsage usage() {
        return new LlmUsage(calls, inputTokens, outputTokens);
    }
}
