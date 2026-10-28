package com.latestay.negotiation;

/** Thrown when a turn arrives for a session that has already ended. */
public class SessionClosedException extends RuntimeException {

    private final SessionStatus status;

    public SessionClosedException(String sessionId, SessionStatus status) {
        super("Negotiation " + sessionId + " is " + status);
        this.status = status;
    }

    public SessionStatus status() {
        return status;
    }
}
