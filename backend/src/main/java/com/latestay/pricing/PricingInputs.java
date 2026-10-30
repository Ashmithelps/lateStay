package com.latestay.pricing;

import com.latestay.negotiation.PricingContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Builds the engine's {@link PricingContext} for a hotel's night: rooms free (rooms_available) out of its total
 * rooms, and demand = weekend factor x season multiplier, clamped to 70-150%. Search, the hotel page and new
 * negotiations all use this, so the "from" price is the price a negotiation opens at.
 */
@Component
public class PricingInputs {

    private final PricingProperties properties;
    private final Seasons seasons;

    public PricingInputs(PricingProperties properties, Seasons seasons) {
        this.properties = properties;
        this.seasons = seasons;
    }

    public PricingContext context(String town, LocalDate nightOf, int roomsFree, int totalRooms) {
        if (properties.version() < 2) {
            return PricingContext.LEGACY;
        }
        return PricingContext.of(roomsFree, totalRooms, demandPercent(nightOf, seasons.percentFor(town, nightOf)));
    }

    /** Weekend (Friday and Saturday nights) times season, clamped to the engine's 70-150%. */
    int demandPercent(LocalDate nightOf, int seasonPercent) {
        return demand(nightOf, seasonPercent, properties.weekendPercent());
    }

    static int demand(LocalDate nightOf, int seasonPercent, int weekendPercent) {
        DayOfWeek day = nightOf.getDayOfWeek();
        int weekend = day == DayOfWeek.FRIDAY || day == DayOfWeek.SATURDAY ? weekendPercent : 100;
        int combined = Math.round(weekend * seasonPercent / 100f);
        return Math.max(PricingContext.MIN_DEMAND_PERCENT, Math.min(PricingContext.MAX_DEMAND_PERCENT, combined));
    }
}
