package com.latestay.negotiation;

/**
 * What the hotel's night looks like when a negotiation opens: how many rooms are still free out of how many, and
 * how busy the night is (weekend and season, as a percentage of normal). Version 1 ignores all of it (the rules
 * before occupancy pricing, kept for sessions opened under them); version 2 uses it.
 *
 * <p>None of this ever moves the floor: it only changes the opening offer and the size of each concession.
 */
public record PricingContext(int version, int roomsFree, int totalRooms, int demandPercent) {

    public static final int MIN_DEMAND_PERCENT = 70;
    public static final int MAX_DEMAND_PERCENT = 150;

    /** The rules before occupancy, weekend and season pricing. */
    public static final PricingContext LEGACY = new PricingContext(1, 1, 1, 100);

    public PricingContext {
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("version must be 1 or 2");
        }
        if (totalRooms < 1) {
            throw new IllegalArgumentException("totalRooms must be at least 1");
        }
        if (roomsFree < 0) {
            throw new IllegalArgumentException("roomsFree must not be negative");
        }
        if (demandPercent < MIN_DEMAND_PERCENT || demandPercent > MAX_DEMAND_PERCENT) {
            throw new IllegalArgumentException("demandPercent must be between " + MIN_DEMAND_PERCENT + " and "
                    + MAX_DEMAND_PERCENT);
        }
    }

    public static PricingContext of(int roomsFree, int totalRooms, int demandPercent) {
        return new PricingContext(2, roomsFree, totalRooms, demandPercent);
    }

    boolean occupancyPricing() {
        return version >= 2;
    }

    /** Rooms free, at least 1 (a negotiation only opens with a room free) and at most the hotel's total. */
    int free() {
        return Math.max(1, Math.min(roomsFree, totalRooms));
    }

    boolean lastRoom() {
        return free() == 1;
    }

    /** A quarter of the rooms or fewer are free, or only one: open higher and concede more slowly. */
    boolean scarce() {
        return occupancyPricing() && (lastRoom() || free() * 4 <= totalRooms);
    }

    /** Half the rooms or more are free: open lower. */
    boolean halfEmpty() {
        return free() * 2 >= totalRooms;
    }
}
