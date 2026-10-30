package com.latestay.stay;

import com.latestay.negotiation.SlotType;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/** Stay windows built from "now", for searches made on the traveller's behalf (a rescue). */
public final class SlotTimes {

    private SlotTimes() {
    }

    /**
     * Arriving at the next quarter hour (or 04:00 if that falls in the 03:00-04:00 gap between the slots), leaving
     * at {@code leave} if that still fits the slot, otherwise at the slot's leave-by time.
     *
     * @throws InvalidStayException if now is outside both slots (daytime)
     */
    public static StayWindow arrivingNow(LocalDateTime now, LocalDateTime leave) {
        LocalDateTime arrive = now.truncatedTo(ChronoUnit.MINUTES);
        int extra = (15 - arrive.getMinute() % 15) % 15;
        arrive = arrive.plusMinutes(extra);
        LocalTime t = arrive.toLocalTime();
        if (t.isAfter(LocalTime.of(3, 0)) && t.isBefore(LocalTime.of(4, 0))) {
            arrive = arrive.toLocalDate().atTime(4, 0);
        }
        SlotType slot = SlotType.forArrival(arrive.toLocalTime())
                .orElseThrow(() -> new InvalidStayException("no late-night or early-morning slot right now"));
        LocalDateTime leaveBy = arrive.toLocalDate().atTime(slot.leaveBy());
        if (!leaveBy.isAfter(arrive)) {
            leaveBy = leaveBy.plusDays(1);
        }
        LocalDateTime chosen = leave != null && leave.isAfter(arrive) && !leave.isAfter(leaveBy) ? leave : leaveBy;
        return StayWindow.of(arrive, chosen);
    }
}
