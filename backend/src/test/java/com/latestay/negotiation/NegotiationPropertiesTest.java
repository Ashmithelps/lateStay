package com.latestay.negotiation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class NegotiationPropertiesTest {

    private final NegotiationEngine engine = new NegotiationEngine(MutableClock.at("2026-10-28T17:30:00Z"));

    record Setup(PricingPolicy policy, LocalTime arrival, List<TravellerEvent> events) {
    }

    @Property(tries = 2000)
    void pricesStayAboveFloorNeverRiseAndAreRounded(@ForAll("setupsWithOther") Setup setup) {
        PricingPolicy policy = setup.policy();
        List<EngineDecision> decisions = play(setup);

        long previous = Long.MAX_VALUE;
        for (EngineDecision decision : decisions) {
            assertThat(decision.amount()).isGreaterThanOrEqualTo(policy.floorPrice());
            assertThat(decision.amount()).isLessThanOrEqualTo(previous);
            assertThat(decision.amount() % policy.roundTo()).isZero();
            previous = decision.amount();
        }
    }

    @Property(tries = 2000)
    void agreedPriceIsTheLastOfferAndAboveFloor(@ForAll("setupsWithOther") Setup setup) {
        NegotiationSession session = engine.open(setup.policy(), setup.arrival());
        for (TravellerEvent event : setup.events()) {
            if (session.status().isTerminal()) {
                break;
            }
            engine.respond(session, event);
        }

        session.agreedPrice().ifPresent(price -> {
            assertThat(price).isGreaterThanOrEqualTo(setup.policy().floorPrice());
            assertThat(price % setup.policy().roundTo()).isZero();
            assertThat(price).isEqualTo(session.currentOffer());
        });
    }

    @Property(tries = 2000)
    void endsWithinMaxRoundsPlusOneBargainingTurns(@ForAll("bargainingSetups") Setup setup) {
        NegotiationSession session = engine.open(setup.policy(), setup.arrival());
        int turns = 0;
        for (TravellerEvent event : setup.events()) {
            if (session.status().isTerminal()) {
                break;
            }
            engine.respond(session, event);
            turns++;
        }

        assertThat(turns).isLessThanOrEqualTo(setup.policy().maxRounds() + 1);
        assertThat(session.status().isTerminal()).isTrue();
    }

    private List<EngineDecision> play(Setup setup) {
        NegotiationSession session = engine.open(setup.policy(), setup.arrival());
        List<EngineDecision> decisions = new ArrayList<>();
        decisions.add(session.history().getFirst().decision());
        for (TravellerEvent event : setup.events()) {
            if (session.status().isTerminal()) {
                break;
            }
            decisions.add(engine.respond(session, event));
        }
        return decisions;
    }

    @Provide
    Arbitrary<Setup> setupsWithOther() {
        return policyAndArrival().flatMap(pa -> events(pa.policy(), true).list().ofMaxSize(20)
                .map(events -> new Setup(pa.policy(), pa.arrival(), events)));
    }

    /** Exactly maxRounds + 1 bargaining turns, so the session must have ended by the last one. */
    @Provide
    Arbitrary<Setup> bargainingSetups() {
        return policyAndArrival().flatMap(pa -> events(pa.policy(), false).list()
                .ofSize(pa.policy().maxRounds() + 1)
                .map(events -> new Setup(pa.policy(), pa.arrival(), events)));
    }

    private Arbitrary<Setup> policyAndArrival() {
        Arbitrary<Integer> roundTo = Arbitraries.of(1, 10, 25, 50, 100);
        Arbitrary<Long> floor = Arbitraries.longs().between(100, 5000);
        Arbitrary<Integer> markupPercent = Arbitraries.integers().between(0, 300);
        Arbitrary<Integer> maxRounds = Arbitraries.integers().between(1, 8);
        Arbitrary<SlotType> slot = Arbitraries.of(SlotType.class);

        Arbitrary<PricingPolicy> policies = Combinators.combine(floor, markupPercent, slot, maxRounds, roundTo)
                .as((f, markup, s, rounds, step) -> new PricingPolicy(f + f * markup / 100, f, s, rounds, step));

        return policies.flatMap(policy -> arrivals(policy.slotType())
                .map(arrival -> new Setup(policy, arrival, List.of())));
    }

    private Arbitrary<LocalTime> arrivals(SlotType slot) {
        int windowMinutes = NightClock.minutesSinceNoon(slot.arriveUntil()) - NightClock.minutesSinceNoon(slot.arriveFrom());
        return Arbitraries.integers().between(0, windowMinutes).map(m -> slot.arriveFrom().plusMinutes(m));
    }

    private Arbitrary<TravellerEvent> events(PricingPolicy policy, boolean includeOther) {
        Arbitrary<TravellerEvent> counters = Arbitraries.longs()
                .between(1, policy.listPrice() * 3 / 2)
                .map(TravellerEvent.Counter::new);
        Arbitrary<TravellerEvent> nearFloor = Arbitraries.longs()
                .between(Math.max(1, policy.floorPrice() - 100), policy.floorPrice() + 100)
                .map(TravellerEvent.Counter::new);
        Arbitrary<TravellerEvent> accept = Arbitraries.just(new TravellerEvent.Accept());
        Arbitrary<TravellerEvent> askBest = Arbitraries.just(new TravellerEvent.AskBestPrice());
        Arbitrary<TravellerEvent> other = Arbitraries.just(new TravellerEvent.Other());

        return includeOther
                ? Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(6, counters), net.jqwik.api.Tuple.of(4, nearFloor),
                        net.jqwik.api.Tuple.of(1, accept), net.jqwik.api.Tuple.of(2, askBest),
                        net.jqwik.api.Tuple.of(2, other))
                : Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(6, counters), net.jqwik.api.Tuple.of(4, nearFloor),
                        net.jqwik.api.Tuple.of(1, accept), net.jqwik.api.Tuple.of(2, askBest));
    }
}
