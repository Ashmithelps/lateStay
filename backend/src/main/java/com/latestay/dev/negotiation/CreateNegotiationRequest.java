package com.latestay.dev.negotiation;

import com.latestay.negotiation.PricingContext;
import com.latestay.negotiation.SlotType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalTime;

/**
 * A session with arbitrary prices. The optional pricing fields pick the engine's rules: pricingVersion 1 (the rules
 * before occupancy pricing, the default) or 2 with the night's rooms free out of totalRooms and its demand %.
 */
record CreateNegotiationRequest(
        @NotNull @Positive Long listPrice,
        @NotNull @Positive Long floorPrice,
        @NotNull SlotType slotType,
        @NotNull LocalTime arrival,
        @Min(1) @Max(2) Integer pricingVersion,
        @Min(0) @Max(500) Integer roomsFree,
        @Min(1) @Max(500) Integer totalRooms,
        @Min(70) @Max(150) Integer demandPercent) {

    PricingContext context() {
        if (pricingVersion == null || pricingVersion == 1) {
            return PricingContext.LEGACY;
        }
        return PricingContext.of(roomsFree == null ? 1 : roomsFree, totalRooms == null ? 10 : totalRooms,
                demandPercent == null ? 100 : demandPercent);
    }
}
