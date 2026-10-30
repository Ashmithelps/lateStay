package com.latestay.admin;

import com.latestay.hotel.AvailabilitySource;
import com.latestay.hotel.FloorRule;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.RoomType;
import com.latestay.negotiation.SlotType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies for /api/admin. */
final class AdminDtos {

    private AdminDtos() {
    }

    record PoliciesBody(boolean coupleFriendly, boolean localIdOk, @Size(max = 500) String checkInNotes) {

        HotelPolicies toPolicies() {
            return new HotelPolicies(coupleFriendly, localIdOk, checkInNotes);
        }

        static PoliciesBody of(HotelPolicies p) {
            return new PoliciesBody(p.coupleFriendly(), p.localIdOk(), p.checkInNotes());
        }
    }

    record HotelBody(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") @Size(max = 80) String slug,
            @NotBlank String town,
            @NotBlank String address,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @NotBlank @Pattern(regexp = "\\+?[0-9 ]{8,16}") String whatsappNumber,
            List<@Pattern(regexp = "[a-z][a-z0-9_]*") String> amenities,
            @Valid PoliciesBody policies,
            @Min(1) @Max(500) Integer totalRooms,
            Boolean active,
            List<@NotBlank String> photos) {

        Hotel toHotel() {
            return new Hotel(null, name, slug, town, address, lat, lng, whatsappNumber,
                    amenities == null ? List.of() : amenities,
                    policies == null ? HotelPolicies.NONE : policies.toPolicies(), totalRooms == null ? 10 : totalRooms,
                    active == null || active, photos == null ? List.of() : photos);
        }
    }

    record HotelView(UUID id, String name, String slug, String town, String address, double lat, double lng,
            String whatsappNumber, List<String> amenities, PoliciesBody policies, int totalRooms, boolean active,
            List<String> photos) {

        static HotelView of(Hotel h) {
            return new HotelView(h.id(), h.name(), h.slug(), h.town(), h.address(), h.lat(), h.lng(),
                    h.whatsappNumber(), h.amenities(), PoliciesBody.of(h.policies()), h.totalRooms(), h.active(),
                    h.photos());
        }
    }

    record RoomTypeBody(@NotBlank String name, @NotNull @Positive Long listPrice,
            @NotNull @Min(1) @Max(20) Integer maxGuests) {
    }

    record RoomTypeView(UUID id, UUID hotelId, String name, long listPrice, int maxGuests) {

        static RoomTypeView of(RoomType r) {
            return new RoomTypeView(r.id(), r.hotelId(), r.name(), r.listPrice(), r.maxGuests());
        }
    }

    /**
     * @param weekdays bitmask over the night_of weekday, bit 0 = Monday; omitted = every day
     */
    record FloorRuleBody(@NotNull SlotType slotType, @Min(1) @Max(127) Integer weekdays, LocalDate seasonStart,
            LocalDate seasonEnd, @NotNull @Positive Long floorPrice, @Min(0) @Max(24) Integer minHours) {

        FloorRule toRule(UUID roomTypeId) {
            return new FloorRule(null, roomTypeId, slotType, weekdays == null ? FloorRule.EVERY_DAY : weekdays,
                    seasonStart, seasonEnd, floorPrice, minHours == null ? 0 : minHours);
        }
    }

    record AvailabilityBody(@NotNull @Min(0) @Max(500) Integer roomsFree) {
    }

    record AvailabilityView(UUID hotelId, LocalDate nightOf, int roomsFree, AvailabilitySource source) {
    }
}
