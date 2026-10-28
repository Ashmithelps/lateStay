package com.latestay.negotiation;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.negotiation.TravellerEvent.Accept;
import com.latestay.negotiation.TravellerEvent.Counter;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NegotiationScenarioTest {

    private final NegotiationEngine engine = new NegotiationEngine(MutableClock.at("2026-10-28T17:30:00Z"));
    private NegotiationSession session;

    @BeforeEach
    void openAtHalfPastEleven() {
        session = engine.open(PricingPolicy.of(1400, 700, SlotType.LATE_NIGHT), LocalTime.of(23, 30));
    }

    @Test
    void opensAtSeventyPercentRoundedUp() {
        assertThat(session.currentOffer()).isEqualTo(1000);
        assertThat(session.history().getFirst().decision())
                .isEqualTo(new EngineDecision(EngineAction.OFFER, 1000, ReasonCode.LATE_HOUR));
    }

    @Test
    void countersFiveHundredSixHundredSevenHundred() {
        // gap 300, 40% = 120 -> 880 -> 900
        assertThat(engine.respond(session, new Counter(500)))
                .isEqualTo(new EngineDecision(EngineAction.OFFER, 900, ReasonCode.COUNTER_TOO_LOW));
        // gap 200, 25% = 50 -> 850
        assertThat(engine.respond(session, new Counter(600)))
                .isEqualTo(new EngineDecision(EngineAction.OFFER, 850, ReasonCode.COUNTER_TOO_LOW));
        // gap 150, 15% = 22.5 -> 827.5 rounds back up to 850, so the minimum step of 50 applies
        assertThat(engine.respond(session, new Counter(700)))
                .isEqualTo(new EngineDecision(EngineAction.OFFER, 800, ReasonCode.MEETING_HALFWAY));

        assertThat(session.status()).isEqualTo(SessionStatus.OPEN);
        assertThat(session.round()).isEqualTo(3);
        assertThat(session.history()).hasSize(4);
    }

    @Test
    void acceptAgreesAtCurrentOffer() {
        engine.respond(session, new Counter(500));
        engine.respond(session, new Counter(600));

        assertThat(engine.respond(session, new Accept()))
                .isEqualTo(new EngineDecision(EngineAction.AGREE, 850, ReasonCode.ACCEPTED));
        assertThat(session.status()).isEqualTo(SessionStatus.AGREED);
        assertThat(session.agreedPrice()).contains(850L);
    }

    @Test
    void fourthCounterGivesFinalOfferThenLowCounterEndsWithNoDeal() {
        engine.respond(session, new Counter(500));
        engine.respond(session, new Counter(600));
        engine.respond(session, new Counter(700));

        // gap 100, 10% = 10 -> 790 -> 800, minimum step -> 750; round 4 of 4
        assertThat(engine.respond(session, new Counter(700)))
                .isEqualTo(new EngineDecision(EngineAction.FINAL_OFFER, 750, ReasonCode.MEETING_HALFWAY));
        assertThat(session.status()).isEqualTo(SessionStatus.FINAL_OFFER);

        assertThat(engine.respond(session, new Counter(700)))
                .isEqualTo(new EngineDecision(EngineAction.HOLD, 750, ReasonCode.ALREADY_FINAL));
        assertThat(session.status()).isEqualTo(SessionStatus.NO_DEAL);
        assertThat(session.agreedPrice()).isEmpty();
    }
}
