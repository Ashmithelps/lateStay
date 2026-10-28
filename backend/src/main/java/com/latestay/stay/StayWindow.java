package com.latestay.stay;

import com.latestay.negotiation.SlotType;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A requested stay, checked against LateStay's slots. Times are local (Asia/Kolkata).
 *
 * @param nightOf the night the stay belongs to: an arrival up to 09:00 (the end of the early-morning window) belongs
 *     to the previous date's night, so the owner's evening availability answer covers the whole night
 */
public record StayWindow(LocalDateTime arrive, LocalDateTime leave, SlotType slotType, LocalDate nightOf) {

    /** The last arrival that still counts as the previous night (the end of the early-morning window). */
    static final LocalTime NIGHT_ENDS = LocalTime.of(9, 0);

    public static StayWindow of(LocalDateTime arrive, LocalDateTime leave) {
        Objects.requireNonNull(arrive, "arrive");
        Objects.requireNonNull(leave, "leave");
        if (!leave.isAfter(arrive)) {
            throw new InvalidStayException("leave must be after arrive");
        }
        SlotType slot = SlotType.forArrival(arrive.toLocalTime()).orElseThrow(() -> new InvalidStayException(
                "arrive must be between 21:00 and 03:00 (late night) or 04:00 and 09:00 (early morning)"));
        LocalDateTime leaveBy = arrive.toLocalDate().atTime(slot.leaveBy());
        if (!leaveBy.isAfter(arrive)) {
            leaveBy = leaveBy.plusDays(1);
        }
        if (leave.isAfter(leaveBy)) {
            throw new InvalidStayException("a " + slot + " stay arriving at " + arrive.toLocalTime()
                    + " must leave by " + leaveBy);
        }
        return new StayWindow(arrive, leave, slot, nightOf(arrive));
    }

    public static LocalDate nightOf(LocalDateTime arrive) {
        return arrive.toLocalTime().isAfter(NIGHT_ENDS) ? arrive.toLocalDate() : arrive.toLocalDate().minusDays(1);
    }

    /** Whole hours between arrive and leave. */
    public int hours() {
        return (int) Duration.between(arrive, leave).toHours();
    }
}
