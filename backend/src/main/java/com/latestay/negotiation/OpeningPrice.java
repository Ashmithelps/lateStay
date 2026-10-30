package com.latestay.negotiation;

import java.time.LocalTime;

/**
 * Opening offer. Later arrivals open lower. Under occupancy pricing ({@link PricingContext} version 2) the hotel's
 * night moves it too: the last free room opens at 95% of list or more (and never below a scarce hotel), a hotel with a quarter of its rooms or fewer
 * free opens 10 points higher, a half-empty one 10 points lower, and the result is scaled by the night's demand
 * (weekend, season) and capped at the list price. Never below the floor.
 */
final class OpeningPrice {

    private static final int TEN_PM = NightClock.minutesSinceNoon(LocalTime.of(22, 0));
    private static final int ELEVEN_PM = NightClock.minutesSinceNoon(LocalTime.of(23, 0));
    private static final int HALF_PAST_MIDNIGHT = NightClock.minutesSinceNoon(LocalTime.of(0, 30));

    static final int LAST_ROOM_PERCENT = 95;
    static final int OCCUPANCY_POINTS = 10;

    private OpeningPrice() {
    }

    /** Percentage of list by arrival time alone. */
    static int percentOfList(LocalTime arrival) {
        int t = NightClock.minutesSinceNoon(arrival);
        if (t < TEN_PM) {
            return 90;
        }
        if (t < ELEVEN_PM) {
            return 80;
        }
        if (t < HALF_PAST_MIDNIGHT) {
            return 70;
        }
        return 60;
    }

    /** Percentage of list after the hotel's occupancy, before demand. */
    static int percentOfList(PricingPolicy policy, LocalTime arrival) {
        int percent = percentOfList(arrival);
        PricingContext context = policy.context();
        if (!context.occupancyPricing()) {
            return percent;
        }
        int scarcePercent = Math.min(100, percent + OCCUPANCY_POINTS);
        if (context.lastRoom()) {
            return Math.max(scarcePercent, LAST_ROOM_PERCENT);
        }
        if (context.scarce()) {
            return scarcePercent;
        }
        if (context.halfEmpty()) {
            return percent - OCCUPANCY_POINTS;
        }
        return percent;
    }

    static long amount(PricingPolicy policy, LocalTime arrival) {
        PricingContext context = policy.context();
        if (!context.occupancyPricing()) {
            long opening = Prices.percentRoundedUp(policy.listPrice(), percentOfList(arrival), policy.roundTo());
            return Math.max(opening, policy.effectiveFloor());
        }
        long percentTimesDemand = (long) percentOfList(policy, arrival) * context.demandPercent();
        long opening = Math.ceilDiv(policy.listPrice() * percentTimesDemand, 10_000L * policy.roundTo())
                * policy.roundTo();
        long cap = policy.listPrice() / policy.roundTo() * policy.roundTo();
        return Math.max(Math.min(opening, cap), policy.effectiveFloor());
    }
}
