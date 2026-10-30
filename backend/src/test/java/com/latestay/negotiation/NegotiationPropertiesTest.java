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

        Arbitrary<PricingPolicy> policies = Combinators.combine(floor, markupPercent, slot, maxRounds, roundTo,
                        contexts())
                .as((f, markup, s, rounds, step, context) ->
                        new PricingPolicy(f + f * markup / 100, f, s, rounds, step, context));

        return policies.flatMap(policy -> arrivals(policy.slotType())
                .map(arrival -> new Setup(policy, arrival, List.of())));
    }

    /** The rules before occupancy pricing, and random nights under it (rooms free can exceed the total). */
    private Arbitrary<PricingContext> contexts() {
        Arbitrary<PricingContext> occupancy = Combinators.combine(
                        Arbitraries.integers().between(0, 60), Arbitraries.integers().between(1, 50),
                        Arbitraries.integers().between(PricingContext.MIN_DEMAND_PERCENT,
                                PricingContext.MAX_DEMAND_PERCENT))
                .as(PricingContext::of);
        return Arbitraries.frequencyOf(net.jqwik.api.Tuple.of(1, Arbitraries.just(PricingContext.LEGACY)),
                net.jqwik.api.Tuple.of(4, occupancy));
    }

    @Property(tries = 2000)
    void openingIsNeverAboveListOrBelowFloor(@ForAll("setupsWithOther") Setup setup) {
        PricingPolicy policy = setup.policy();
        long opening = engine.openingOffer(policy, setup.arrival());
        assertThat(opening).isGreaterThanOrEqualTo(policy.floorPrice());
        assertThat(opening % policy.roundTo()).isZero();
        if (policy.context().version() == 2) {
            long cap = policy.listPrice() / policy.roundTo() * policy.roundTo();
            assertThat(opening).isLessThanOrEqualTo(Math.max(cap, Prices.roundUp(policy.floorPrice(), policy.roundTo())));
        }
    }

    /** Fewer rooms free never opens lower, all else equal; the last room opens at least as high as anything. */
    @Property(tries = 2000)
    void scarcerRoomsNeverOpenLower(@ForAll("setupsWithOther") Setup setup,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 50) int total,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 50) int fewer,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 50) int extra,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 70, max = 150) int demand) {
        PricingPolicy p = setup.policy();
        int scarce = Math.min(fewer, total);
        int roomier = Math.min(scarce + extra, total);
        long scarceOpening = engine.openingOffer(withContext(p, PricingContext.of(scarce, total, demand)), setup.arrival());
        long roomierOpening = engine.openingOffer(withContext(p, PricingContext.of(roomier, total, demand)), setup.arrival());
        long lastRoom = engine.openingOffer(withContext(p, PricingContext.of(1, total, demand)), setup.arrival());
        assertThat(scarceOpening).isGreaterThanOrEqualTo(roomierOpening);
        assertThat(lastRoom).isGreaterThanOrEqualTo(scarceOpening);
    }

    /** A busier night (higher demand) never opens lower, all else equal. */
    @Property(tries = 1000)
    void busierNightsNeverOpenLower(@ForAll("setupsWithOther") Setup setup,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 70, max = 150) int a,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 70, max = 150) int b) {
        PricingPolicy p = setup.policy();
        PricingContext c = p.context().version() == 2 ? p.context() : PricingContext.of(3, 10, 100);
        long low = engine.openingOffer(withContext(p, PricingContext.of(c.roomsFree(), c.totalRooms(), Math.min(a, b))),
                setup.arrival());
        long high = engine.openingOffer(withContext(p, PricingContext.of(c.roomsFree(), c.totalRooms(), Math.max(a, b))),
                setup.arrival());
        assertThat(high).isGreaterThanOrEqualTo(low);
    }

    /** Version 1 is exactly the engine before occupancy pricing. */
    @Property(tries = 1000)
    void legacyOpeningIsUnchanged(@ForAll("setupsWithOther") Setup setup) {
        PricingPolicy p = withContext(setup.policy(), PricingContext.LEGACY);
        long expected = Math.max(Prices.percentRoundedUp(p.listPrice(), OpeningPrice.percentOfList(setup.arrival()),
                p.roundTo()), Prices.roundUp(p.floorPrice(), p.roundTo()));
        assertThat(engine.openingOffer(p, setup.arrival())).isEqualTo(expected);
    }

    private static PricingPolicy withContext(PricingPolicy p, PricingContext context) {
        return new PricingPolicy(p.listPrice(), p.floorPrice(), p.slotType(), p.maxRounds(), p.roundTo(), context);
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
