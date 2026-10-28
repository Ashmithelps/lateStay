package com.latestay.session;

import com.latestay.conversation.HotelFacts;
import com.latestay.hotel.HotelPolicies;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns stored hotel data into the facts the front desk may talk about. */
final class HotelFactsMapper {

    private static final Map<String, String> AMENITY_LABELS = Map.of(
            "heater", "Room heater",
            "hot_water", "Hot water all night",
            "parking", "Parking",
            "late_check_in", "Late check-in",
            "near_bus_stand", "Close to the bus stand",
            "wifi", "Wi-Fi",
            "geyser", "Geyser in the bathroom",
            "mountain_view", "Mountain view",
            "power_backup", "Power backup",
            "food", "Food on request");

    private HotelFactsMapper() {
    }

    static HotelFacts facts(String name, String town, List<String> amenities, HotelPolicies policies) {
        List<String> labels = new ArrayList<>(amenities.stream().map(HotelFactsMapper::label).toList());
        if (policies.coupleFriendly()) {
            labels.add("Couple-friendly");
        }
        List<String> rules = new ArrayList<>();
        rules.add(policies.localIdOk() ? "Local IDs are accepted" : "Local IDs are not accepted; bring an outstation ID");
        if (policies.checkInNotes() != null && !policies.checkInNotes().isBlank()) {
            rules.add(policies.checkInNotes());
        }
        return new HotelFacts(name + ", " + town, labels, rules);
    }

    static String label(String code) {
        String known = AMENITY_LABELS.get(code);
        if (known != null) {
            return known;
        }
        String words = code.replace('_', ' ');
        return words.isEmpty() ? words : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }
}
