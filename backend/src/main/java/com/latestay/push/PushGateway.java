package com.latestay.push;

/** Sends one notification to one device. */
public interface PushGateway {

    enum Result {
        SENT,
        /** FCM says the token is no longer valid (app removed, token rotated): delete it. */
        INVALID_TOKEN,
        /** Anything else; the message is tried again. */
        FAILED
    }

    boolean canSend();

    Result send(String token, PushPayload payload);
}
