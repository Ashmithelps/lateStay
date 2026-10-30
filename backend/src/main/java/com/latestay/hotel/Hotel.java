package com.latestay.hotel;

import java.util.List;
import java.util.UUID;

/** A hotel as stored. {@code photos} are URLs in display order; {@code totalRooms} counts all its rooms. */
public record Hotel(UUID id, String name, String slug, String town, String address, double lat, double lng,
        String whatsappNumber, List<String> amenities, HotelPolicies policies, int totalRooms, boolean active,
        List<String> photos) {

    public Hotel {
        amenities = List.copyOf(amenities);
        photos = List.copyOf(photos);
        policies = policies == null ? HotelPolicies.NONE : policies;
    }

    public Hotel withId(UUID newId) {
        return new Hotel(newId, name, slug, town, address, lat, lng, whatsappNumber, amenities, policies, totalRooms,
                active, photos);
    }
}
