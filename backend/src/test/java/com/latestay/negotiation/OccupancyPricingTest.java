package com.latestay.negotiation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class OccupancyPricingTest {

    private final NegotiationEngine engine = new NegotiationEngine(MutableClock.at("2026-10-28T17:30:00Z"));
    private static final LocalTime HALF_PAST_ELEVEN = LocalTime.of(23, 30); // 70% of list by time alone

    private long opening(int free, int total, int demand) {
        return engine.openingOffer(PricingPolicy.of(2000, 800, SlotType.LATE_NIGHT,
                PricingContext.of(free, total, demand)), HALF_PAST_ELEVEN);
    }

    @Test
    void lastRoomOpensNearList() {
        assertThat(opening(1, 10, 100)).isEqualTo(1900);
    }

    @Test
    void scarceOpensTenPointsHigherAndHalfEmptyTenLower() {
        assertThat(opening(2, 10, 100)).isEqualTo(1600);
        assertThat(opening(4, 10, 100)).isEqualTo(1400);
        assertThat(opening(5, 10, 100)).isEqualTo(1200);
        assertThat(opening(10, 10, 100)).isEqualTo(1200);
    }

    @Test
    void demandScalesTheOpeningButNeverAboveList() {
        assertThat(opening(4, 10, 110)).isEqualTo(1550); // 1,540 rounded up to 50
        assertThat(opening(4, 10, 85)).isEqualTo(1200); // 1,190 rounded up
        assertThat(opening(1, 10, 150)).isEqualTo(2000);
    }

    @Test
    void neverBelowTheFloor() {
        long opening = engine.openingOffer(PricingPolicy.of(1000, 900, SlotType.LATE_NIGHT,
                PricingContext.of(10, 10, 70)), LocalTime.of(1, 0));
        assertThat(opening).isEqualTo(900);
    }

    @Test
    void scarceRoomsConcedeHalfAsFast() {
        PricingPolicy roomy = PricingPolicy.of(2000, 800, SlotType.LATE_NIGHT, PricingContext.of(4, 10, 100));
        PricingPolicy scarce = PricingPolicy.of(2000, 800, SlotType.LATE_NIGHT, PricingContext.of(2, 10, 100));
        NegotiationSession a = engine.open(roomy, HALF_PAST_ELEVEN);
        NegotiationSession b = engine.open(scarce, HALF_PAST_ELEVEN);
        long roomyDrop = a.currentOffer() - engine.respond(a, new TravellerEvent.Counter(800)).amount();
        long scarceDrop = b.currentOffer() - engine.respond(b, new TravellerEvent.Counter(800)).amount();
        assertThat(roomyDrop).isEqualTo(200); // 40% of the 600 gap, rounded up to 50
        assertThat(scarceDrop).isEqualTo(150); // 20% of the 800 gap, rounded up to 50
    }

    @Test
    void legacyIgnoresOccupancy() {
        long legacy = engine.openingOffer(PricingPolicy.of(2000, 800, SlotType.LATE_NIGHT), HALF_PAST_ELEVEN);
        assertThat(legacy).isEqualTo(1400);
    }
}
