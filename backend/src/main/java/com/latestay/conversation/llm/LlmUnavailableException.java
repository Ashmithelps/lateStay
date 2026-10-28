package com.latestay.conversation.llm;

/** The LLM gave no usable answer: not configured, timed out, errored, refused or was cut off. */
public class LlmUnavailableException extends RuntimeException {

    private final boolean timedOut;

    public LlmUnavailableException(String message) {
        this(message, null, false);
    }

    public LlmUnavailableException(String message, Throwable cause, boolean timedOut) {
        super(message, cause);
        this.timedOut = timedOut;
    }

    public boolean timedOut() {
        return timedOut;
    }
}
