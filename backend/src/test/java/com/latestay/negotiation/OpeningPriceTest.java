package com.latestay.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class OpeningPriceTest {

    private final NegotiationEngine engine = new NegotiationEngine(MutableClock.at("2026-10-28T17:30:00Z"));

    @ParameterizedTest
    @CsvSource({
            "21:00, 90", "21:59, 90",
            "22:00, 80", "22:59, 80",
            "23:00, 70", "00:00, 70", "00:29, 70",
            "00:30, 60", "03:00, 60",
            "04:00, 60", "09:00, 60",
    })
    void percentByArrivalBand(String arrival, int percent) {
        assertThat(OpeningPrice.percentOfList(LocalTime.parse(arrival))).isEqualTo(percent);
    }

    @ParameterizedTest
    @CsvSource({
            "21:30, 1300, OPENING",   // 90% of 1400 = 1260
            "22:30, 1150, LATE_HOUR", // 80% = 1120
            "23:30, 1000, LATE_HOUR", // 70% = 980
            "01:00,  850, LATE_HOUR", // 60% = 840
    })
    void openingOfferRoundsUp(String arrival, long expected, ReasonCode reason) {
        NegotiationSession session = engine.open(PricingPolicy.of(1400, 500, SlotType.LATE_NIGHT), LocalTime.parse(arrival));

        assertThat(session.currentOffer()).isEqualTo(expected);
        assertThat(session.history().getFirst().decision())
                .isEqualTo(new EngineDecision(EngineAction.OFFER, expected, reason));
    }

    @Test
    void openingNeverBelowFloorAndIsFinalWhenAtFloor() {
        // 60% of 1400 = 840, floor 1000
        NegotiationSession session = engine.open(PricingPolicy.of(1400, 1000, SlotType.LATE_NIGHT), LocalTime.of(2, 0));

        assertThat(session.currentOffer()).isEqualTo(1000);
        assertThat(session.status()).isEqualTo(SessionStatus.FINAL_OFFER);
        assertThat(session.history().getFirst().decision())
                .isEqualTo(new EngineDecision(EngineAction.FINAL_OFFER, 1000, ReasonCode.FLOOR_REACHED));
    }

    @Test
    void floorThatIsNotAMultipleIsRoundedUp() {
        NegotiationSession session = engine.open(PricingPolicy.of(1400, 1010, SlotType.LATE_NIGHT), LocalTime.of(2, 0));

        assertThat(session.currentOffer()).isEqualTo(1050);
    }

    @Test
    void earlyMorningUsesTheLateBand() {
        NegotiationSession session = engine.open(PricingPolicy.of(1000, 300, SlotType.EARLY_MORNING), LocalTime.of(6, 0));

        assertThat(session.currentOffer()).isEqualTo(600);
    }

    @ParameterizedTest
    @CsvSource({"LATE_NIGHT, 20:59", "LATE_NIGHT, 03:01", "LATE_NIGHT, 06:00", "EARLY_MORNING, 03:59", "EARLY_MORNING, 09:01"})
    void rejectsArrivalOutsideSlotWindow(SlotType slot, String arrival) {
        PricingPolicy policy = PricingPolicy.of(1400, 700, slot);

        assertThatThrownBy(() -> engine.open(policy, LocalTime.parse(arrival)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opensWithArrivalFromClockWhenNotGiven() {
        // 17:30Z is 23:00 IST -> 70%
        NegotiationSession session = engine.open(PricingPolicy.of(1400, 500, SlotType.LATE_NIGHT));

        assertThat(session.arrival()).isEqualTo(LocalTime.of(23, 0));
        assertThat(session.currentOffer()).isEqualTo(1000);
    }
}
