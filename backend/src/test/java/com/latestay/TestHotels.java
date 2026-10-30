package com.latestay;

import com.latestay.hotel.AvailabilityRepository;
import com.latestay.hotel.AvailabilitySource;
import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.RoomType;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.negotiation.SlotType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates hotels for integration tests. Slugs are unique per call; positions are offsets from a centre. */
@Component
public class TestHotels {

    /** Degrees of latitude per kilometre. */
    public static final double KM = 1 / 111.32;

    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final FloorRuleRepository floorRules;
    private final AvailabilityRepository availability;

    public TestHotels(HotelRepository hotels, RoomTypeRepository roomTypes, FloorRuleRepository floorRules,
            AvailabilityRepository availability) {
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.floorRules = floorRules;
        this.availability = availability;
    }

    public Hotel hotel(String name, double lat, double lng, List<String> amenities, HotelPolicies policies,
            boolean active) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + UUID.randomUUID().toString().substring(0, 8);
        return hotels.insert(new Hotel(null, name, slug, "Testpur", "1 Test Road", lat, lng, uniqueWhatsAppNumber(),
                amenities, policies, 10, active, List.of("/seed/" + slug + "-1.png")));
    }

    /** Each test hotel gets its own owner number, so WhatsApp messages match exactly one hotel. */
    public static String uniqueWhatsAppNumber() {
        long digits = 7_000_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 2_999_999_999L);
        String number = Long.toString(digits);
        return "+91 " + number.substring(0, 5) + " " + number.substring(5);
    }

    /** A room type with an every-day floor rule for each given slot. */
    public RoomType room(Hotel hotel, long listPrice, int maxGuests, long floorPrice, SlotType... slots) {
        RoomType room = roomTypes.insert(hotel.id(), "Room " + listPrice, listPrice, maxGuests);
        for (SlotType slot : slots) {
            floorRules.insert(new FloorRule(null, room.id(), slot, FloorRule.EVERY_DAY, null, null, floorPrice, 0));
        }
        return room;
    }

    public void roomsFree(Hotel hotel, LocalDate nightOf, int rooms) {
        availability.upsert(hotel.id(), nightOf, rooms, AvailabilitySource.WHATSAPP);
    }
}
