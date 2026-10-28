package com.latestay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock tests set and move by hand. */
public final class TestClock extends Clock {

    private volatile Instant now;

    public TestClock(Instant now) {
        this.now = now;
    }

    public void set(Instant instant) {
        now = instant;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("Asia/Kolkata");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
