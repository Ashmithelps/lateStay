package com.latestay.conversation;

import java.util.List;

/** What the front desk may say about the hotel. Replies may only answer questions from these facts. */
public record HotelFacts(String name, List<String> amenities, List<String> checkInRules) {

    public HotelFacts {
        amenities = List.copyOf(amenities);
        checkInRules = List.copyOf(checkInRules);
    }
}
