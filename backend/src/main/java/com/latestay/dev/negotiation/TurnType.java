package com.latestay.dev.negotiation;

import com.latestay.negotiation.TravellerEvent;

enum TurnType {
    COUNTER,
    ACCEPT,
    ASK_BEST_PRICE,
    OTHER;

    static TurnType of(TravellerEvent event) {
        return switch (event) {
            case TravellerEvent.Counter counter -> COUNTER;
            case TravellerEvent.Accept accept -> ACCEPT;
            case TravellerEvent.AskBestPrice ask -> ASK_BEST_PRICE;
            case TravellerEvent.Other other -> OTHER;
        };
    }
}
