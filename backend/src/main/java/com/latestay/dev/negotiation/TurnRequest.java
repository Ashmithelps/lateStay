package com.latestay.dev.negotiation;

import com.latestay.negotiation.TravellerEvent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

record TurnRequest(@NotNull TurnType type, @Positive Long amount) {

    TravellerEvent toEvent() {
        return switch (type) {
            case COUNTER -> {
                if (amount == null) {
                    throw new IllegalArgumentException("amount is required for COUNTER");
                }
                yield new TravellerEvent.Counter(amount);
            }
            case ACCEPT -> new TravellerEvent.Accept();
            case ASK_BEST_PRICE -> new TravellerEvent.AskBestPrice();
            case OTHER -> new TravellerEvent.Other();
        };
    }
}
