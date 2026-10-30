package com.latestay.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PricingInputsTest {

    static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 11);
    static final LocalDate FRIDAY = LocalDate.of(2026, 11, 13);
    static final LocalDate SATURDAY = LocalDate.of(2026, 11, 14);
    static final LocalDate SUNDAY = LocalDate.of(2026, 11, 15);

    @Test
    void fridayAndSaturdayNightsAreTheWeekend() {
        assertThat(PricingInputs.demand(WEDNESDAY, 100, 110)).isEqualTo(100);
        assertThat(PricingInputs.demand(FRIDAY, 100, 110)).isEqualTo(110);
        assertThat(PricingInputs.demand(SATURDAY, 100, 110)).isEqualTo(110);
        assertThat(PricingInputs.demand(SUNDAY, 100, 110)).isEqualTo(100);
    }

    @Test
    void weekendAndSeasonMultiplyAndAreClampedToTheEnginesRange() {
        assertThat(PricingInputs.demand(FRIDAY, 120, 110)).isEqualTo(132);
        assertThat(PricingInputs.demand(SATURDAY, 200, 110)).isEqualTo(150);
        assertThat(PricingInputs.demand(WEDNESDAY, 50, 110)).isEqualTo(70);
        assertThat(PricingInputs.demand(WEDNESDAY, 85, 110)).isEqualTo(85);
    }
}
