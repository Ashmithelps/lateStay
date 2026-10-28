package com.latestay.negotiation;

/** Exact integer price arithmetic. */
final class Prices {

    private static final int[] STEP_PERCENTS = {40, 25, 15, 10};

    private Prices() {
    }

    static long roundUp(long amount, int roundTo) {
        return Math.ceilDiv(amount, roundTo) * (long) roundTo;
    }

    /** {@code percent}% of {@code amount}, rounded up to {@code roundTo}. */
    static long percentRoundedUp(long amount, int percent, int roundTo) {
        return Math.ceilDiv(amount * percent, 100L * roundTo) * (long) roundTo;
    }

    /** Concession percentage for a 1-based round; rounds past the table use the last entry. */
    static int stepPercent(int round) {
        return STEP_PERCENTS[Math.min(round, STEP_PERCENTS.length) - 1];
    }

    /**
     * The next offer after conceding {@code percent}% of the gap between the current offer and
     * {@code target}. Rounded up, at least one {@code roundTo} lower than the current offer, never
     * below {@code floor}.
     */
    static long concede(long currentOffer, long target, int percent, int roundTo, long floor) {
        long gap = currentOffer - target;
        long next = Math.ceilDiv(currentOffer * 100 - gap * percent, 100L * roundTo) * (long) roundTo;
        next = Math.min(next, currentOffer - roundTo);
        return Math.max(next, floor);
    }
}
