package com.latestay.negotiation;

public enum SessionStatus {
    OPEN,
    AGREED,
    FINAL_OFFER,
    NO_DEAL,
    EXPIRED;

    public boolean isTerminal() {
        return this == AGREED || this == NO_DEAL || this == EXPIRED;
    }
}
