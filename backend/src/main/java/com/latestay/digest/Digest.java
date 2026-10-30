package com.latestay.digest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The morning numbers for one night (night_of = the night that just ended), on WhatsApp at 09:00 and on
 * /admin/today.
 *
 * <ul>
 *   <li>bookings, checkedIn, completed, noShows, rescues: bookings for that night (paid ones; by their state now)
 *   <li>refunds, refundsPaise: refunds that went through between {@code from} and {@code to}
 *   <li>openDisputes: every booking in dispute now, any night
 *   <li>commissionPaise: LateStay's commission on that night's bookings the hotel is owed for, net of promo codes
 *   <li>owedToHotelsPaise: everything not yet paid out to hotels
 *   <li>noAnswer: active hotels that didn't answer that night's availability ping
 *   <li>llmCostPaise: LLM spend between {@code from} and {@code to}, in paise at USD_INR_RATE
 * </ul>
 */
public record Digest(LocalDate night, Instant from, Instant to, int bookings, int checkedIn, int completed,
        int noShows, int rescues, int openDisputes, int refunds, long refundsPaise, long commissionPaise,
        long owedToHotelsPaise, List<String> noAnswer, long llmCostPaise, long llmCostMicroUsd) {
}
