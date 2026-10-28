package com.latestay.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.latestay.stay.LateStayTime;
import com.latestay.stay.StayWindow;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local profile only: inserts the fictional hotels in seed/local-hotels.json once, and on every start reports
 * availability for tonight and tomorrow night so search always has something to find.
 */
@Component
@Profile("local")
class LocalSeedData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeedData.class);
    static final int PHOTOS_PER_HOTEL = 5;
    private static final int WEEKEND = FloorRule.bit(DayOfWeek.FRIDAY) | FloorRule.bit(DayOfWeek.SATURDAY);

    record SeedFile(List<SeedHotel> hotels) {
    }

    record SeedHotel(String slug, String name, String town, String address, double lat, double lng, String whatsapp,
            BigDecimal rating, List<Integer> roomsFree, List<String> amenities, SeedPolicies policies,
            List<SeedRoom> rooms) {
    }

    record SeedPolicies(boolean coupleFriendly, boolean localIdOk, String checkInNotes) {
    }

    record SeedRoom(String name, long listPrice, int maxGuests, int floorPercent, Integer weekendFloorPercent) {
    }

    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final FloorRuleRepository floorRules;
    private final AvailabilityRepository availability;
    private final ObjectMapper json;
    private final Clock clock;

    LocalSeedData(HotelRepository hotels, RoomTypeRepository roomTypes, FloorRuleRepository floorRules,
            AvailabilityRepository availability, ObjectMapper json, Clock clock) {
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.floorRules = floorRules;
        this.availability = availability;
        this.json = json.copy().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        SeedFile seed;
        try (InputStream in = new ClassPathResource("seed/local-hotels.json").getInputStream()) {
            seed = json.readValue(in, SeedFile.class);
        }
        LocalDate tonight = StayWindow.nightOf(LocalDateTime.now(clock.withZone(LateStayTime.ZONE)));
        int inserted = 0;
        for (SeedHotel s : seed.hotels()) {
            Hotel hotel = hotels.findBySlug(s.slug()).orElse(null);
            if (hotel == null) {
                hotel = insert(s);
                inserted++;
            }
            availability.upsert(hotel.id(), tonight, s.roomsFree().get(0), AvailabilitySource.WHATSAPP);
            availability.upsert(hotel.id(), tonight.plusDays(1), s.roomsFree().get(1), AvailabilitySource.WHATSAPP);
        }
        log.info("Local seed: {} hotels inserted, availability set for the nights of {} and {}", inserted, tonight,
                tonight.plusDays(1));
    }

    private Hotel insert(SeedHotel s) {
        List<String> photos = IntStream.rangeClosed(1, PHOTOS_PER_HOTEL)
                .mapToObj(i -> "/seed/" + s.slug() + "-" + i + ".png").toList();
        Hotel hotel = hotels.insert(new Hotel(null, s.name(), s.slug(), s.town(), s.address(), s.lat(), s.lng(),
                s.whatsapp(), s.amenities(),
                new HotelPolicies(s.policies().coupleFriendly(), s.policies().localIdOk(), s.policies().checkInNotes()),
                s.rating(), true, photos));
        for (SeedRoom r : s.rooms()) {
            RoomType room = roomTypes.insert(hotel.id(), r.name(), r.listPrice(), r.maxGuests());
            for (SlotType slot : SlotType.values()) {
                floorRules.insert(new FloorRule(null, room.id(), slot, FloorRule.EVERY_DAY, null, null,
                        floor(r.listPrice(), r.floorPercent()), 0));
            }
            if (r.weekendFloorPercent() != null) {
                floorRules.insert(new FloorRule(null, room.id(), SlotType.LATE_NIGHT, WEEKEND, null, null,
                        floor(r.listPrice(), r.weekendFloorPercent()), 0));
            }
        }
        return hotel;
    }

    /** Rounded to ₹10. */
    static long floor(long listPrice, int percent) {
        return Math.round(listPrice * percent / 1000.0) * 10;
    }
}
