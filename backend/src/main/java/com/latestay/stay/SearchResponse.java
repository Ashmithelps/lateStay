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
     * @param fromPrice the engine's opening price for this arrival, for the cheapest fitting room type
     */
    public record HotelResult(UUID id, String slug, String name, String town, double lat, double lng,
            List<String> photos, List<String> amenities, double distanceKm, int roomsFree, @Schema(nullable = true) BigDecimal rating,
            long fromPrice, List<RoomOption> roomTypes) {
    }

    public record RoomOption(UUID id, String name, int maxGuests, long fromPrice) {
    }
}
