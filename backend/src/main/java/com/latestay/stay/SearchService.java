package com.latestay.stay;

import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.hotel.FloorRuleResolver;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.RoomType;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.stay.SearchResponse.HotelResult;
import com.latestay.stay.SearchResponse.RoomOption;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hotels within {@link #RADIUS_METRES} that have rooms free for the stay's night, a room type that fits the
 * guests and has a floor rule for the stay, and every requested filter. Prices come from the engine.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    public static final int RADIUS_METRES = 10_000;
    public static final int MAX_GUESTS = 20;

    private record Candidate(UUID id, String slug, String name, String town, double lat, double lng,
            List<String> amenities, BigDecimal rating, int roomsFree, double distanceMetres) {
    }

    private final JdbcClient jdbc;
    private final StayRequestRepository stayRequests;
    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final FloorRuleRepository floorRules;
    private final NegotiationEngine engine;

    public SearchService(JdbcClient jdbc, StayRequestRepository stayRequests, HotelRepository hotels,
            RoomTypeRepository roomTypes, FloorRuleRepository floorRules, NegotiationEngine engine) {
        this.jdbc = jdbc;
        this.stayRequests = stayRequests;
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.floorRules = floorRules;
        this.engine = engine;
    }

    @Transactional
    public SearchResponse search(double lat, double lng, StayWindow window, int guests, List<SearchFilter> filters) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new InvalidStayException("lat/lng out of range");
        }
        if (guests < 1 || guests > MAX_GUESTS) {
            throw new InvalidStayException("guests must be between 1 and " + MAX_GUESTS);
        }
        StayRequest request = stayRequests.insert(lat, lng, window, guests, filters);
        return new SearchResponse(request.id(), window.nightOf(), window.slotType(),
                results(lat, lng, window, guests, filters));
    }

    /** The same search for a stored request, without storing a new one (nearest first, then cheapest). */
    public List<HotelResult> results(StayRequest request) {
        return results(request.lat(), request.lng(), request.window(), request.guests(), request.filters());
    }

    private List<HotelResult> results(double lat, double lng, StayWindow window, int guests,
            List<SearchFilter> filters) {
        List<Candidate> candidates = candidates(lat, lng, window, filters);
        List<UUID> hotelIds = candidates.stream().map(Candidate::id).toList();
        Map<UUID, List<RoomType>> rooms = roomTypes.fitting(hotelIds, guests).stream()
                .collect(Collectors.groupingBy(RoomType::hotelId));
        Map<UUID, List<FloorRule>> rules = floorRules.forRoomTypes(
                        rooms.values().stream().flatMap(List::stream).map(RoomType::id).toList(), window.slotType())
                .stream().collect(Collectors.groupingBy(FloorRule::roomTypeId));
        Map<UUID, List<String>> photos = hotels.photos(hotelIds);

        List<HotelResult> results = new ArrayList<>();
        for (Candidate hotel : candidates) {
            List<RoomOption> options = options(rooms.getOrDefault(hotel.id(), List.of()), rules, window);
            if (options.isEmpty()) {
                continue;
            }
            results.add(new HotelResult(hotel.id(), hotel.slug(), hotel.name(), hotel.town(), hotel.lat(), hotel.lng(),
                    photos.getOrDefault(hotel.id(), List.of()), hotel.amenities(),
                    Math.round(hotel.distanceMetres() / 10.0) / 100.0, hotel.roomsFree(), hotel.rating(),
                    options.getFirst().fromPrice(), options));
        }
        results.sort(Comparator.comparingDouble(HotelResult::distanceKm).thenComparingLong(HotelResult::fromPrice));
        return results;
    }

    /** Room types of one hotel that fit the guests and have a floor rule for the stay, cheapest first. */
    public List<RoomOption> roomOptions(UUID hotelId, StayWindow window, int guests) {
        List<RoomType> rooms = roomTypes.fitting(List.of(hotelId), guests);
        Map<UUID, List<FloorRule>> rules = floorRules.forRoomTypes(rooms.stream().map(RoomType::id).toList(),
                window.slotType()).stream().collect(Collectors.groupingBy(FloorRule::roomTypeId));
        return options(rooms, rules, window);
    }

    private List<RoomOption> options(List<RoomType> rooms, Map<UUID, List<FloorRule>> rules, StayWindow window) {
        return rooms.stream()
                .map(room -> option(room, rules.getOrDefault(room.id(), List.of()), window))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingLong(RoomOption::fromPrice))
                .toList();
    }

    /** The engine's opening price for this room, or empty if no floor rule applies. */
    private Optional<RoomOption> option(RoomType room, List<FloorRule> rules, StayWindow window) {
        return FloorRuleResolver.resolve(rules, window.slotType(), window.nightOf(), window.hours()).flatMap(rule -> {
            try {
                PricingPolicy policy = PricingPolicy.of(room.listPrice(), rule.floorPrice(), window.slotType());
                return Optional.of(new RoomOption(room.id(), room.name(), room.maxGuests(),
                        engine.openingOffer(policy, window.arrive().toLocalTime())));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping room type {}: {}", room.id(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    private List<Candidate> candidates(double lat, double lng, StayWindow window, List<SearchFilter> filters) {
        String[] amenities = filters.stream().map(SearchFilter::amenity).filter(a -> a != null).toArray(String[]::new);
        return jdbc.sql("""
                        SELECT h.id, h.slug, h.name, h.town, ST_Y(h.location::geometry) AS lat,
                               ST_X(h.location::geometry) AS lng, h.amenities, h.rating, a.rooms_free,
                               ST_Distance(h.location, p.g) AS distance_m
                        FROM hotel h
                        CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography AS g) p
                        JOIN nightly_availability a
                          ON a.hotel_id = h.id AND a.night_of = :night AND a.rooms_free > 0
                        WHERE h.active
                          AND ST_DWithin(h.location, p.g, :radius)
                          AND h.amenities @> CAST(:amenities AS text[])
                          AND (NOT :coupleFriendly OR COALESCE((h.policies ->> 'couple_friendly')::boolean, false))
                          AND (NOT :localIdOk OR COALESCE((h.policies ->> 'local_id_ok')::boolean, false))""")
                .param("lat", lat).param("lng", lng).param("night", window.nightOf())
                .param("radius", RADIUS_METRES)
                .param("amenities", amenities)
                .param("coupleFriendly", filters.contains(SearchFilter.COUPLE_FRIENDLY))
                .param("localIdOk", filters.contains(SearchFilter.LOCAL_ID_OK))
                .query(SearchService::candidate).list();
    }

    private static Candidate candidate(ResultSet rs, int row) throws SQLException {
        java.sql.Array amenities = rs.getArray("amenities");
        return new Candidate(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("town"), rs.getDouble("lat"), rs.getDouble("lng"),
                amenities == null ? List.of() : List.of((String[]) amenities.getArray()), rs.getBigDecimal("rating"),
                rs.getInt("rooms_free"), rs.getDouble("distance_m"));
    }
}
