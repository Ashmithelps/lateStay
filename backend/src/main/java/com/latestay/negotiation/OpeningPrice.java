package com.latestay.negotiation;

import java.time.LocalTime;

/** Opening offer by arrival time: later arrivals open lower. */
final class OpeningPrice {

    private static final int TEN_PM = NightClock.minutesSinceNoon(LocalTime.of(22, 0));
    private static final int ELEVEN_PM = NightClock.minutesSinceNoon(LocalTime.of(23, 0));
    private static final int HALF_PAST_MIDNIGHT = NightClock.minutesSinceNoon(LocalTime.of(0, 30));

    private OpeningPrice() {
    }

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

    static long amount(PricingPolicy policy, LocalTime arrival) {
        long opening = Prices.percentRoundedUp(policy.listPrice(), percentOfList(arrival), policy.roundTo());
        return Math.max(opening, policy.effectiveFloor());
    }
}
