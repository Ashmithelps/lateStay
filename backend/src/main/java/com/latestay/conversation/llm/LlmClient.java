package com.latestay.conversation.llm;

public interface LlmClient {

    /**
     * Returns the model's text (a JSON document when the request carries a schema) and token usage.
     *
     * @throws LlmUnavailableException when no usable answer came back
     */
    LlmResponse complete(LlmRequest request);

    /** The model the calls go to, for the cost log; null when there is no model. */
    default String model() {
        return null;
    }
}
