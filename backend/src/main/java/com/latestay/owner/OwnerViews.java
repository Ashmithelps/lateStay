package com.latestay.owner;

import com.latestay.negotiation.SlotType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** What the owner page shows. Money in paise for earnings, rupees for room prices (as the admin panel). */
public final class OwnerViews {

    private OwnerViews() {
    }

    /**
     * @param roomsFree the owner's latest report for tonight, or null if they haven't answered
     * @param roomsAvailable what LateStay can still sell tonight (report minus holds and new bookings)
     * @param reliability the last 30 days; pausedAt set while the hotel is hidden from search for review
     * @param reviews the hotel's rating and latest reviews, as travellers see them
     */
    public record OwnerSummary(String hotelName, String town, LocalDate tonight, Integer roomsFree,
            int roomsAvailable, List<TonightBooking> bookings, Earnings earnings, List<OwnerSettlement> settlements,
            List<OwnerRoom> rooms, com.latestay.reliability.HotelReliability reliability,
            com.latestay.review.ReviewDtos.HotelReviews reviews) {
    }

    /** status: AWAITING_CONFIRMATION, CONFIRMED, CHECKED_IN, NO_SHOW, COMPLETED or DISPUTED. */
    /** @param rooms 2 for a group's two rooms */
    public record TonightBooking(UUID id, String guestName, int guests, int rooms, LocalDateTime arriveAt,
            String code, long payAtDesk, String status, boolean checkedIn) {
    }

    /**
     * @param weekStart Monday of this week (Asia/Kolkata)
     * @param thisWeekPaise earned since then (deposits minus commission, refunds, adjustments)
     * @param owedPaise everything not yet paid out
     */
    public record Earnings(LocalDate weekStart, long thisWeekPaise, long owedPaise, LocalDate nextPayoutDate) {
    }

    public record OwnerSettlement(UUID id, LocalDate periodStart, LocalDate periodEnd, long amountPaise,
            String status, String upiReference, Instant paidAt) {
    }

    public record OwnerRoom(UUID id, String name, long listPrice, List<OwnerFloor> floors) {
    }

    public record OwnerFloor(UUID id, SlotType slotType, int weekdays, LocalDate seasonStart, LocalDate seasonEnd,
            int minHours, long floorPrice) {
    }
}
