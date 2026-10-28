package com.latestay.negotiation;

/** What the traveller did on their turn, already interpreted (by the LLM or the UI). */
public sealed interface TravellerEvent {

    record Counter(long amount) implements TravellerEvent {
        public Counter {
            if (amount <= 0) {
                throw new IllegalArgumentException("counter amount must be positive");
            }
        }
    }

    record Accept() implements TravellerEvent {
    }

    record AskBestPrice() implements TravellerEvent {
    }

    /** Anything that is not a price move: questions, chatter, unparseable input. */
    record Other() implements TravellerEvent {
    }
}
