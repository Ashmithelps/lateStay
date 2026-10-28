package com.latestay.conversation;

public class UnknownConversationException extends RuntimeException {

    public UnknownConversationException(String id) {
        super("No negotiation " + id);
    }
}
