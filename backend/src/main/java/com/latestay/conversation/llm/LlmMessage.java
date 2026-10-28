package com.latestay.conversation.llm;

public record LlmMessage(Role role, String content) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }
}
