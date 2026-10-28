package com.latestay.hotel;

import com.latestay.negotiation.SlotType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Picks the floor rule for one stay. Of the rules that apply, the most specific wins: a seasonal rule over an
 * all-year one, then the higher {@code minHours}, then the higher floor. No rule means the room isn't offered.
 */
public final class FloorRuleResolver {

    private static final Comparator<FloorRule> MOST_SPECIFIC_FIRST = Comparator
            .comparing(FloorRule::isSeasonal).reversed()
            .thenComparing(Comparator.comparingInt(FloorRule::minHours).reversed())
            .thenComparing(Comparator.comparingLong(FloorRule::floorPrice).reversed());

    private FloorRuleResolver() {
    }

    public static Optional<FloorRule> resolve(Collection<FloorRule> rules, SlotType slot, LocalDate nightOf,
            int stayHours) {
        return rules.stream()
                .filter(rule -> rule.appliesTo(slot, nightOf, stayHours))
                .min(MOST_SPECIFIC_FIRST);
    }
}
