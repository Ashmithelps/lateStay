package com.latestay.negotiation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

final class MutableClock extends Clock {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Instant now;

    MutableClock(Instant now) {
        this.now = now;
    }

    static MutableClock at(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant));
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return IST;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return now;
    }
}
