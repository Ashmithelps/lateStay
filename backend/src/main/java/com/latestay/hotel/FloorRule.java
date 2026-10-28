package com.latestay.hotel;

import com.latestay.negotiation.SlotType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A room type's lowest acceptable price for some nights.
 *
 * @param weekdays bitmask over the weekday of night_of: bit 0 = Monday ... bit 6 = Sunday
 * @param seasonStart first night_of the rule covers, inclusive; null with seasonEnd = all year
 * @param minHours shortest stay, in hours, the rule applies to
 */
public record FloorRule(UUID id, UUID roomTypeId, SlotType slotType, int weekdays, LocalDate seasonStart,
        LocalDate seasonEnd, long floorPrice, int minHours) {

    public static final int EVERY_DAY = 0b111_1111;

    public static int bit(DayOfWeek day) {
        return 1 << (day.getValue() - 1);
    }

    public boolean isSeasonal() {
        return seasonStart != null;
    }

    public boolean appliesTo(SlotType slot, LocalDate nightOf, int stayHours) {
        return slotType == slot
                && (weekdays & bit(nightOf.getDayOfWeek())) != 0
                && (!isSeasonal() || (!nightOf.isBefore(seasonStart) && !nightOf.isAfter(seasonEnd)))
                && stayHours >= minHours;
    }
}
