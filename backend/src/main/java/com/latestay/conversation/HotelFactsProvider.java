package com.latestay.conversation;

import com.latestay.negotiation.NegotiationSession;

public interface HotelFactsProvider {

    HotelFacts factsFor(NegotiationSession session);
}
