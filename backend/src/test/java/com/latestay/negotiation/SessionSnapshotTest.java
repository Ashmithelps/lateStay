package com.latestay.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latestay.negotiation.TravellerEvent.Counter;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SessionSnapshotTest {

    private final NegotiationEngine engine = new NegotiationEngine(MutableClock.at("2026-10-28T17:30:00Z"));
    private final PricingPolicy policy = PricingPolicy.of(1400, 700, SlotType.LATE_NIGHT);

    @Test
    void restoredSessionContinuesExactlyLikeTheOriginal() {
        NegotiationSession original = engine.open(policy, LocalTime.of(23, 30));
        NegotiationSession copy = engine.open(policy, LocalTime.of(23, 30));
        engine.respond(original, new Counter(500));
        engine.respond(copy, new Counter(500));

        NegotiationSession restored = engine.restore(copy.snapshot());

        assertThat(restored.snapshot()).isEqualTo(copy.snapshot());
        for (long counter : new long[] {600, 700, 700, 700}) {
            if (original.status().isTerminal()) {
                break;
            }
            assertThat(engine.respond(restored, new Counter(counter))).isEqualTo(engine.respond(original, new Counter(counter)));
        }
        assertThat(restored.snapshot()).usingRecursiveComparison().ignoringFields("id").isEqualTo(original.snapshot());
    }

    @Test
    void snapshotRejectsInconsistentState() {
        Instant now = Instant.parse("2026-10-28T17:30:00Z");
        LocalTime arrival = LocalTime.of(23, 30);

        assertThatThrownBy(() -> new SessionSnapshot("x", policy, arrival, now, now, 600, 1, SessionStatus.OPEN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionSnapshot("x", policy, arrival, now, now, 900, 1, SessionStatus.AGREED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionSnapshot("x", policy, arrival, now, now, 900, 1, SessionStatus.OPEN, 900L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openingOfferMatchesOpen() {
        assertThat(engine.openingOffer(policy, LocalTime.of(23, 30)))
                .isEqualTo(engine.open(policy, LocalTime.of(23, 30)).currentOffer())
                .isEqualTo(1000);
        assertThatThrownBy(() -> engine.openingOffer(policy, LocalTime.of(6, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"21:00, LATE_NIGHT", "02:59, LATE_NIGHT", "03:00, LATE_NIGHT", "04:00, EARLY_MORNING",
            "09:00, EARLY_MORNING"})
    void slotForArrival(LocalTime arrival, SlotType slot) {
        assertThat(SlotType.forArrival(arrival)).contains(slot);
    }

    @ParameterizedTest
    @CsvSource({"03:30", "09:01", "12:00", "20:59"})
    void noSlotOutsideWindows(LocalTime arrival) {
        assertThat(SlotType.forArrival(arrival)).isEmpty();
    }
}
