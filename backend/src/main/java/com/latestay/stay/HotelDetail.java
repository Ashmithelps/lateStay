package com.latestay.stay;

import io.swagger.v3.oas.annotations.media.Schema;
import com.latestay.negotiation.SlotType;
import com.latestay.stay.SearchResponse.RoomOption;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A hotel page. {@code stay} is present when the page was opened from a search: it carries tonight's distance,
 * rooms and prices for that stay request.
 */
public record HotelDetail(UUID id, String slug, String name, String town, String address, double lat, double lng,
        List<String> photos, List<String> amenities, Policies policies, @Schema(nullable = true) BigDecimal rating,
        @Schema(nullable = true) Stay stay) {

    public record Policies(boolean coupleFriendly, boolean localIdOk, @Schema(nullable = true) String checkInNotes) {
    }

    /**
     * @param arriveFrom start of the slot's arrival window
     * @param arriveUntil end of the slot's arrival window
     * @param leaveBy latest time to leave for this slot
     * @param fromPrice the engine's opening price for the cheapest fitting room type; null when none fits
     */
    public record Stay(UUID stayRequestId, LocalDate nightOf, SlotType slotType, LocalDateTime arrive,
            LocalDateTime leave, LocalTime arriveFrom, LocalTime arriveUntil, LocalTime leaveBy, int guests,
            double distanceKm, int roomsFree, @Schema(nullable = true) Long fromPrice, List<RoomOption> roomTypes) {
    }
}
