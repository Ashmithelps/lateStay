package com.latestay.reliability;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A hotel's last {@code window} of bookings.
 *
 * @param confirmRatePercent confirmed / (confirmed + declined + timed out), rounded; null with no decisions
 * @param medianMinutesToConfirm from payment to the owner's Confirm; null with no confirmations
 * @param rescues admin Rescues (HOTEL_FAILED) that weren't settling a dispute
 * @param upheldDisputes disputes that went the guest's way: Rescue of a disputed booking, or "I did arrive" settled
 *     as stayed
 * @param score 0-100 for search ranking; a hotel with fewer than minDecisions decisions starts at 80
 * @param pausedAt hidden from search since then, until an admin reviews it
 */
public record HotelReliability(int confirmed, int declined, int timedOut,
        @Schema(nullable = true) Integer confirmRatePercent, @Schema(nullable = true) Double medianMinutesToConfirm,
        int rescues, int upheldDisputes, int score, boolean isNew, @Schema(nullable = true) Instant pausedAt) {

    public static final int NEW_SCORE = 80;
    static final int STRIKE_POINTS = 10;
    static final int SLOW_POINTS = 5;

    public int decisions() {
        return confirmed + declined + timedOut;
    }

    public int strikes() {
        return rescues + upheldDisputes;
    }

    /** The numbers as counted, with score and isNew worked out. */
    static HotelReliability of(int confirmed, int declined, int timedOut, Double median, int rescues, int upheld,
            Instant pausedAt, ReliabilityProperties p) {
        int decisions = confirmed + declined + timedOut;
        Integer rate = decisions == 0 ? null : (int) Math.round(confirmed * 100.0 / decisions);
        boolean isNew = decisions < p.minDecisions();
        int score = isNew || rate == null ? NEW_SCORE : rate;
        score -= STRIKE_POINTS * (rescues + upheld);
        if (median != null && median > p.slowMinutes()) {
            score -= SLOW_POINTS;
        }
        return new HotelReliability(confirmed, declined, timedOut, rate, median, rescues, upheld,
                Math.max(0, Math.min(100, score)), isNew, pausedAt);
    }

    /** Below the threshold: not enough confirmations, or too many rescues and upheld disputes. */
    boolean belowThreshold(ReliabilityProperties p) {
        return (decisions() >= p.minDecisions() && confirmRatePercent != null
                        && confirmRatePercent < p.minConfirmRatePercent())
                || strikes() >= p.maxStrikes();
    }
}
