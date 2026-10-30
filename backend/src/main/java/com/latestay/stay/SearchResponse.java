package com.latestay.stay;

import io.swagger.v3.oas.annotations.media.Schema;
import com.latestay.negotiation.SlotType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @param stayRequestId pass this to POST /api/negotiations together with a room type id
 */
public record SearchResponse(UUID stayRequestId, LocalDate nightOf, SlotType slotType, List<HotelResult> results) {

    /**
     * @param rating the Bayesian average of the hotel's reviews; null ("New") until it has 3
     * @param reviewCount how many reviews the hotel has
     * @param fromPrice the engine's opening price for this arrival, for the cheapest option
     * @param roomTypes single rooms that fit the guests or, when none does, pairs of two rooms (rooms = 2)
     */
    public record HotelResult(UUID id, String slug, String name, String town, double lat, double lng,
            List<String> photos, List<String> amenities, double distanceKm, int roomsFree,
            @Schema(nullable = true) BigDecimal rating, int reviewCount, long fromPrice, List<RoomOption> roomTypes) {
    }

    /**
     * One room, or two rooms negotiated together.
     *
     * @param id the (first) room type id
     * @param roomTypeIds every room type in the option, in order (the same type twice for two of one room)
     * @param name "Deluxe" or "Deluxe + Standard"
     * @param maxGuests the guests all the option's rooms sleep together
     */
    public record RoomOption(UUID id, List<UUID> roomTypeIds, int rooms, String name, int maxGuests,
            long fromPrice) {
    }
}
