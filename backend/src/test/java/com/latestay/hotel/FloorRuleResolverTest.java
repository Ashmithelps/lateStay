package com.latestay.hotel;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.negotiation.SlotType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FloorRuleResolverTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 4);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 7);
    private static final int WEEKEND = FloorRule.bit(DayOfWeek.FRIDAY) | FloorRule.bit(DayOfWeek.SATURDAY);

    private static FloorRule rule(SlotType slot, int weekdays, LocalDate start, LocalDate end, long floor, int minHours) {
        return new FloorRule(UUID.randomUUID(), UUID.randomUUID(), slot, weekdays, start, end, floor, minHours);
    }

    private final FloorRule base = rule(SlotType.LATE_NIGHT, FloorRule.EVERY_DAY, null, null, 700, 0);
    private final FloorRule weekend = rule(SlotType.LATE_NIGHT, WEEKEND, null, null, 900, 0);
    private final FloorRule longStay = rule(SlotType.LATE_NIGHT, FloorRule.EVERY_DAY, null, null, 800, 8);
    private final FloorRule diwali = rule(SlotType.LATE_NIGHT, FloorRule.EVERY_DAY,
            LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 10), 1100, 0);
    private final FloorRule morning = rule(SlotType.EARLY_MORNING, FloorRule.EVERY_DAY, null, null, 500, 0);
    private final List<FloorRule> all = List.of(base, weekend, longStay, diwali, morning);

    @Test
    void picksBySlot() {
        assertThat(FloorRuleResolver.resolve(all, SlotType.EARLY_MORNING, WEDNESDAY, 6)).contains(morning);
        assertThat(FloorRuleResolver.resolve(List.of(base), SlotType.EARLY_MORNING, WEDNESDAY, 6)).isEmpty();
    }

    @Test
    void weekdayBitsUseTheNightOf() {
        assertThat(FloorRuleResolver.resolve(List.of(base, weekend), SlotType.LATE_NIGHT, WEDNESDAY, 6)).contains(base);
        assertThat(FloorRuleResolver.resolve(List.of(base, weekend), SlotType.LATE_NIGHT, SATURDAY, 6)).contains(weekend);
    }

    @Test
    void higherMinHoursWinsWhenTheStayIsLongEnough() {
        assertThat(FloorRuleResolver.resolve(List.of(base, longStay), SlotType.LATE_NIGHT, WEDNESDAY, 7)).contains(base);
        assertThat(FloorRuleResolver.resolve(List.of(base, longStay), SlotType.LATE_NIGHT, WEDNESDAY, 8))
                .contains(longStay);
    }

    @Test
    void seasonalRuleBeatsEverythingInsideItsSeason() {
        assertThat(FloorRuleResolver.resolve(all, SlotType.LATE_NIGHT, SATURDAY, 9)).contains(diwali);
        assertThat(FloorRuleResolver.resolve(all, SlotType.LATE_NIGHT, LocalDate.of(2026, 11, 11), 9)).contains(longStay);
    }

    @Test
    void tieGoesToTheHigherFloor() {
        FloorRule other = rule(SlotType.LATE_NIGHT, FloorRule.EVERY_DAY, null, null, 750, 0);
        assertThat(FloorRuleResolver.resolve(List.of(base, other), SlotType.LATE_NIGHT, WEDNESDAY, 6)).contains(other);
    }
}
