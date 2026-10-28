package com.latestay.hotel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stored as jsonb with snake_case keys: couple_friendly, local_id_ok, check_in_notes. */
public record HotelPolicies(boolean coupleFriendly, boolean localIdOk, String checkInNotes) {

    public static final HotelPolicies NONE = new HotelPolicies(false, false, null);

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("couple_friendly", coupleFriendly);
        json.put("local_id_ok", localIdOk);
        json.put("check_in_notes", checkInNotes);
        return json;
    }

    public static HotelPolicies fromJson(Map<String, Object> json) {
        return new HotelPolicies(
                Boolean.TRUE.equals(json.get("couple_friendly")),
                Boolean.TRUE.equals(json.get("local_id_ok")),
                json.get("check_in_notes") instanceof String notes ? notes : null);
    }
}
