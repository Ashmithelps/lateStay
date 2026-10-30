package com.latestay.negotiation;

import java.util.Objects;

/**
 * A hotel's pricing rules for one negotiation. Amounts are whole rupees. For two rooms, list and floor are the sums
 * of both rooms' list and floor prices.
 */
public record PricingPolicy(long listPrice, long floorPrice, SlotType slotType, int maxRounds, int roundTo,
        PricingContext context) {

    public static final int DEFAULT_MAX_ROUNDS = 4;
    public static final int DEFAULT_ROUND_TO = 50;

    public PricingPolicy {
        Objects.requireNonNull(slotType, "slotType");
        Objects.requireNonNull(context, "context");
        if (floorPrice <= 0) {
            throw new IllegalArgumentException("floorPrice must be positive");
        }
        if (listPrice < floorPrice) {
            throw new IllegalArgumentException("listPrice must be at least floorPrice");
        }
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be at least 1");
        }
        if (roundTo < 1) {
            throw new IllegalArgumentException("roundTo must be positive");
        }
    }

    /** The rules before occupancy pricing ({@link PricingContext#LEGACY}). */
    public PricingPolicy(long listPrice, long floorPrice, SlotType slotType, int maxRounds, int roundTo) {
        this(listPrice, floorPrice, slotType, maxRounds, roundTo, PricingContext.LEGACY);
    }

    public static PricingPolicy of(long listPrice, long floorPrice, SlotType slotType) {
        return new PricingPolicy(listPrice, floorPrice, slotType, DEFAULT_MAX_ROUNDS, DEFAULT_ROUND_TO);
    }

    public static PricingPolicy of(long listPrice, long floorPrice, SlotType slotType, PricingContext context) {
        return new PricingPolicy(listPrice, floorPrice, slotType, DEFAULT_MAX_ROUNDS, DEFAULT_ROUND_TO, context);
    }

    /** The lowest amount the engine may offer: the floor rounded up to {@link #roundTo}. */
    long effectiveFloor() {
        return Prices.roundUp(floorPrice, roundTo);
    }
}
