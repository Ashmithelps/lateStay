package com.latestay.negotiation;

import java.time.LocalTime;

/**
 * The part of the night a traveller books. Arrival windows are inclusive and may cross midnight.
 */
public enum SlotType {
    LATE_NIGHT(LocalTime.of(21, 0), LocalTime.of(3, 0), LocalTime.of(10, 0)),
    EARLY_MORNING(LocalTime.of(4, 0), LocalTime.of(9, 0), LocalTime.of(14, 0));

    private final LocalTime arriveFrom;
    private final LocalTime arriveUntil;
    private final LocalTime leaveBy;

    SlotType(LocalTime arriveFrom, LocalTime arriveUntil, LocalTime leaveBy) {
        this.arriveFrom = arriveFrom;
        this.arriveUntil = arriveUntil;
        this.leaveBy = leaveBy;
    }

    public LocalTime arriveFrom() {
        return arriveFrom;
    }

    public LocalTime arriveUntil() {
        return arriveUntil;
    }

    public LocalTime leaveBy() {
        return leaveBy;
    }

    public boolean accepts(LocalTime arrival) {
        int t = NightClock.minutesSinceNoon(arrival);
        return t >= NightClock.minutesSinceNoon(arriveFrom) && t <= NightClock.minutesSinceNoon(arriveUntil);
    }
}
