package com.latestay.conversation.llm;

/** Used when no provider or key is configured. Callers fall back to deterministic code. */
public class NoLlmClient implements LlmClient {

    private final String reason;

    public NoLlmClient(String reason) {
        this.reason = reason;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        throw new LlmUnavailableException("LLM disabled: " + reason);
    }
}
