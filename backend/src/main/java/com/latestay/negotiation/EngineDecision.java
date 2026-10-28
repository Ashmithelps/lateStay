package com.latestay.negotiation;

import java.util.Objects;

/** The engine's answer to one turn. {@code amount} is the price on the table after the turn. */
public record EngineDecision(EngineAction action, long amount, ReasonCode reasonCode) {

    public EngineDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }
}
