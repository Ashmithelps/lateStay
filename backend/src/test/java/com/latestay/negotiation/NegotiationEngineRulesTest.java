package com.latestay.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latestay.negotiation.TravellerEvent.Accept;
import com.latestay.negotiation.TravellerEvent.AskBestPrice;
import com.latestay.negotiation.TravellerEvent.Counter;
import com.latestay.negotiation.TravellerEvent.Other;
import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class NegotiationEngineRulesTest {

    private static final LocalTime HALF_PAST_ELEVEN = LocalTime.of(23, 30);

    private final MutableClock clock = MutableClock.at("2026-10-28T17:30:00Z");
    private final NegotiationEngine engine = new NegotiationEngine(clock);

    /** Opens at 1000 (70% of 1400). */
    private NegotiationSession open(long floor) {
        return engine.open(PricingPolicy.of(1400, floor, SlotType.LATE_NIGHT), HALF_PAST_ELEVEN);
    }

    @Test
    void counterAtOrAboveOfferAgreesAtOfferNotCounter() {
        NegotiationSession session = open(700);

        assertThat(engine.respond(session, new Counter(1200)))
                .isEqualTo(new EngineDecision(EngineAction.AGREE, 1000, ReasonCode.ACCEPTED));
        assertThat(session.agreedPrice()).contains(1000L);
    }

    @Test
    void counterWithinFiftyAndAboveFloorAgreesAtCounter() {
        NegotiationSession session = open(700);

        assertThat(engine.respond(session, new Counter(950)))
                .isEqualTo(new EngineDecision(EngineAction.AGREE, 950, ReasonCode.CLOSE_ENOUGH));
    }

    @Test
    void closeEnoughCounterIsRoundedUp() {
        NegotiationSession session = open(700);

        assertThat(engine.respond(session, new Counter(951)))
                .isEqualTo(new EngineDecision(EngineAction.AGREE, 1000, ReasonCode.CLOSE_ENOUGH));
    }

    @Test
    void closeCounterBelowFloorIsNotAgreed() {
        NegotiationSession session = engine.open(
                new PricingPolicy(1400, 970, SlotType.LATE_NIGHT, 4, 10), HALF_PAST_ELEVEN);
        assertThat(session.currentOffer()).isEqualTo(980);

        assertThat(engine.respond(session, new Counter(940)))
                .isEqualTo(new EngineDecision(EngineAction.FINAL_OFFER, 970, ReasonCode.FLOOR_REACHED));
    }

    @Test
    void askBestPriceConcedesOneStepTowardsFloor() {
        NegotiationSession session = open(700);

        // gap 300, 40% = 120 -> 880 -> 900
        assertThat(engine.respond(session, new AskBestPrice()))
                .isEqualTo(new EngineDecision(EngineAction.OFFER, 900, ReasonCode.MEETING_HALFWAY));
        assertThat(session.round()).isEqualTo(1);
    }

    @Test
    void reachingFloorIsFinalAndAcceptAgreesAtIt() {
        NegotiationSession session = open(900);

        assertThat(engine.respond(session, new Counter(500)))
                .isEqualTo(new EngineDecision(EngineAction.OFFER, 950, ReasonCode.COUNTER_TOO_LOW));
        assertThat(engine.respond(session, new Counter(500)))
                .isEqualTo(new EngineDecision(EngineAction.FINAL_OFFER, 900, ReasonCode.FLOOR_REACHED));
        assertThat(session.status()).isEqualTo(SessionStatus.FINAL_OFFER);

        assertThat(engine.respond(session, new Accept()))
                .isEqualTo(new EngineDecision(EngineAction.AGREE, 900, ReasonCode.ACCEPTED));
        assertThat(session.status()).isEqualTo(SessionStatus.AGREED);
    }

    @Test
    void counterAtOrAboveFinalOfferAgreesAtFinalOffer() {
        NegotiationSession session = engine.open(
                new PricingPolicy(1400, 700, SlotType.LATE_NIGHT, 1, 50), HALF_PAST_ELEVEN);

        assertThat(engine.respond(session, new Counter(500)))
                .isEqualTo(new EngineDecision(EngineAction.FINAL_OFFER, 900, ReasonCode.COUNTER_TOO_LOW));
        assertThat(engine.respond(session, new Counter(950)))
                .isEqualTo(new EngineDecision(EngineAction.AGREE, 900, ReasonCode.ACCEPTED));
    }

    @Test
    void askBestPriceAfterFinalHoldsAndEndsSession() {
        NegotiationSession session = engine.open(
                new PricingPolicy(1400, 700, SlotType.LATE_NIGHT, 1, 50), HALF_PAST_ELEVEN);
        engine.respond(session, new Counter(500));

        assertThat(engine.respond(session, new AskBestPrice()))
                .isEqualTo(new EngineDecision(EngineAction.HOLD, 900, ReasonCode.ALREADY_FINAL));
        assertThat(session.status()).isEqualTo(SessionStatus.NO_DEAL);
    }

    @Test
    void otherHoldsWithoutUsingARound() {
        NegotiationSession session = open(700);

        assertThat(engine.respond(session, new Other()))
                .isEqualTo(new EngineDecision(EngineAction.HOLD, 1000, ReasonCode.UNCLEAR));
        assertThat(session.round()).isZero();
        assertThat(session.status()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    void otherAfterFinalKeepsFinalOfferOpen() {
        NegotiationSession session = engine.open(
                new PricingPolicy(1400, 700, SlotType.LATE_NIGHT, 1, 50), HALF_PAST_ELEVEN);
        engine.respond(session, new Counter(500));

        assertThat(engine.respond(session, new Other()))
                .isEqualTo(new EngineDecision(EngineAction.HOLD, 900, ReasonCode.UNCLEAR));
        assertThat(session.status()).isEqualTo(SessionStatus.FINAL_OFFER);
    }

    @Test
    void expiredSessionRejectsTurns() {
        NegotiationSession session = open(700);
        clock.advance(NegotiationEngine.DEFAULT_SESSION_TTL.plus(Duration.ofSeconds(1)));

        assertThatThrownBy(() -> engine.respond(session, new Counter(800)))
                .isInstanceOf(SessionClosedException.class);
        assertThat(session.status()).isEqualTo(SessionStatus.EXPIRED);
        assertThat(session.history()).hasSize(1);
    }

    @Test
    void ensureOpenMarksExpiryWithoutMovingThePrice() {
        NegotiationSession session = open(700);
        engine.ensureOpen(session);
        assertThat(session.status()).isEqualTo(SessionStatus.OPEN);

        clock.advance(NegotiationEngine.DEFAULT_SESSION_TTL.plus(Duration.ofSeconds(1)));

        assertThatThrownBy(() -> engine.ensureOpen(session)).isInstanceOf(SessionClosedException.class);
        assertThat(session.status()).isEqualTo(SessionStatus.EXPIRED);
        assertThat(session.currentOffer()).isEqualTo(1000);
    }

    @Test
    void agreedSessionRejectsTurns() {
        NegotiationSession session = open(700);
        engine.respond(session, new Accept());

        assertThatThrownBy(() -> engine.respond(session, new Counter(800)))
                .isInstanceOf(SessionClosedException.class)
                .extracting(e -> ((SessionClosedException) e).status())
                .isEqualTo(SessionStatus.AGREED);
    }

    @Test
    void historyRecordsEveryTurn() {
        NegotiationSession session = open(700);
        engine.respond(session, new Counter(500));
        engine.respond(session, new Other());

        assertThat(session.history())
                .extracting(Turn::round)
                .containsExactly(0, 1, 1);
        assertThat(session.history().get(1).event()).contains(new Counter(500));
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> new Counter(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PricingPolicy.of(600, 700, SlotType.LATE_NIGHT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricingPolicy(1400, 700, SlotType.LATE_NIGHT, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
