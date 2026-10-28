package com.latestay.negotiation;

import java.time.LocalTime;

/** Orders times of day on a noon-to-noon timeline so that 00:30 comes after 23:00. */
final class NightClock {

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int NOON = 12 * 60;

    private NightClock() {
    }

    static int minutesSinceNoon(LocalTime time) {
        int minuteOfDay = time.getHour() * 60 + time.getMinute();
        return Math.floorMod(minuteOfDay - NOON, MINUTES_PER_DAY);
    }
}
