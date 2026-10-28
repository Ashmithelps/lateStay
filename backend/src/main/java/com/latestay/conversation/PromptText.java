package com.latestay.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Helpers for putting untrusted text into prompts as data. */
public final class PromptText {

    public static final int MAX_MESSAGE_CHARS = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private PromptText() {
    }

    /**
     * Serialises a value as JSON with angle brackets escaped, so traveller text can never close or open the
     * surrounding tags.
     */
    public static String quote(Object value) {
        try {
            return JSON.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String truncate(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() <= MAX_MESSAGE_CHARS ? stripped : stripped.substring(0, MAX_MESSAGE_CHARS);
    }
}
