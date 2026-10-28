package com.latestay.conversation;

import com.latestay.negotiation.NegotiationSession;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** One made-up hotel for every session, until hotels are stored. Facts avoid digits on purpose. */
@Component
@Profile("local")
class StubHotelFactsProvider implements HotelFactsProvider {

    private static final HotelFacts DEODAR_NEST = new HotelFacts(
            "Deodar Nest, Shimla",
            List.of("Room heater", "Hot water all night", "Free parking", "Couple-friendly",
                    "Late check-in", "Five minutes' walk from the ISBT bus stand"),
            List.of("Check-in any time through the night with a government photo ID",
                    "Late-night stays leave by ten in the morning, early-morning stays by two in the afternoon",
                    "Pay at the desk on arrival"));

    @Override
    public HotelFacts factsFor(NegotiationSession session) {
        return DEODAR_NEST;
    }
}
