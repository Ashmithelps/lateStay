package com.latestay.stay;

import java.util.Arrays;
import java.util.Locale;

/** Search filter chips. Most match an amenity code; two match hotel policies. */
public enum SearchFilter {
    HEATER("heater", null),
    PARKING("parking", null),
    HOT_WATER("hot_water", null),
    LATE_CHECK_IN("late_check_in", null),
    NEAR_BUS_STAND("near_bus_stand", null),
    COUPLE_FRIENDLY(null, "couple_friendly"),
    LOCAL_ID_OK(null, "local_id_ok");

    private final String amenity;
    private final String policy;

    SearchFilter(String amenity, String policy) {
        this.amenity = amenity;
        this.policy = policy;
    }

    /** null when the filter is a policy. */
    public String amenity() {
        return amenity;
    }

    /** null when the filter is an amenity. */
    public String policy() {
        return policy;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Accepts "heater", "Couple-friendly", "near_bus_stand" and similar. */
    public static SearchFilter parse(String raw) {
        String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return Arrays.stream(values()).filter(f -> f.name().equals(normalized)).findFirst()
                .orElseThrow(() -> new InvalidStayException("unknown filter '" + raw + "'"));
    }
}
