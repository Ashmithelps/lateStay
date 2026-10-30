package com.latestay.pricing;

import java.time.LocalDate;
import java.util.UUID;

/** A busy (or quiet) stretch in one town: nights from startsOn to endsOn, inclusive, at multiplierPercent. */
public record SeasonPeriod(UUID id, String town, LocalDate startsOn, LocalDate endsOn, int multiplierPercent,
        String label) {
}
