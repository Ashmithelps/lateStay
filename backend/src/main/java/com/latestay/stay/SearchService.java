package com.latestay.stay;

import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.RoomType;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.PricingContext;
import com.latestay.pricing.PricingInputs;
import com.latestay.reliability.HotelReliability;
import com.latestay.reliability.Reliability;
import com.latestay.review.Rating;
import com.latestay.review.Ratings;
import com.latestay.stay.SearchResponse.HotelResult;
import com.latestay.stay.SearchResponse.RoomOption;
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
 * Hotels within {@link #RADIUS_METRES} that have rooms free for the stay's night, a room type (or, for a group, two
 * rooms) that fits the guests and has a floor rule for the stay, and every requested filter. Prices come from the
 * engine, with the night's occupancy, weekend and season ({@link PricingInputs}).
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    public static final int RADIUS_METRES = 10_000;
    public static final int MAX_GUESTS = 20;

    private record Candidate(UUID id, String slug, String name, String town, double lat, double lng,
            List<String> amenities, int roomsFree, int totalRooms, double distanceMetres) {
    }

    /** Distance bands for ranking: under 1 km, 1-3 km, 3 km and more. */
    static int band(double distanceKm) {
        return distanceKm < 1 ? 0 : distanceKm < 3 ? 1 : 2;
    }

    /** Most pairs shown for a group at one hotel. */
    static final int MAX_PAIRS = 3;

    private final JdbcClient jdbc;
    private final StayRequestRepository stayRequests;
    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final FloorRuleRepository floorRules;
    private final NegotiationEngine engine;
    private final PricingInputs pricing;
    private final Ratings ratings;
    private final Reliability reliability;
    private final java.time.Clock clock;

    public SearchService(JdbcClient jdbc, StayRequestRepository stayRequests, HotelRepository hotels,
            RoomTypeRepository roomTypes, FloorRuleRepository floorRules, NegotiationEngine engine,
            PricingInputs pricing, Ratings ratings, Reliability reliability, java.time.Clock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.stayRequests = stayRequests;
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.floorRules = floorRules;
        this.engine = engine;
        this.pricing = pricing;
        this.ratings = ratings;
        this.reliability = reliability;
    }

    @Transactional
    public SearchResponse search(double lat, double lng, StayWindow window, int guests, List<SearchFilter> filters) {
        return search(lat, lng, window, guests, filters, Attribution.NONE);
    }

    /** As above, recording where the traveller came from and the town of the nearest result. */
    public SearchResponse search(double lat, double lng, StayWindow window, int guests, List<SearchFilter> filters,
            Attribution attribution) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new InvalidStayException("lat/lng out of range");
        }
        if (guests < 1 || guests > MAX_GUESTS) {
            throw new InvalidStayException("guests must be between 1 and " + MAX_GUESTS);
        }
        var found = results(lat, lng, window, guests, filters);
        StayRequest request = stayRequests.insert(lat, lng, window, guests, filters);
        String nearestTown = found.stream().min(Comparator.comparingDouble(HotelResult::distanceKm))
                .map(HotelResult::town).orElse(null);
        stayRequests.attribute(request.id(), attribution, nearestTown);
        return new SearchResponse(request.id(), window.nightOf(), window.slotType(), found);
    }

    /** The same search for a stored request, without storing a new one. */
    public List<HotelResult> results(StayRequest request) {
        return results(request.lat(), request.lng(), request.window(), request.guests(), request.filters());
    }

    /**
     * Ranked by distance band, then reliability score (highest first), then price, then exact distance. Paused
     * hotels are left out.
     */
    private List<HotelResult> results(double lat, double lng, StayWindow window, int guests,
            List<SearchFilter> filters) {
        List<Candidate> candidates = candidates(lat, lng, window, filters);
        List<UUID> hotelIds = candidates.stream().map(Candidate::id).toList();
        Map<UUID, List<RoomType>> rooms = roomTypes.fitting(hotelIds, 1).stream()
                .collect(Collectors.groupingBy(RoomType::hotelId));
        Map<UUID, List<FloorRule>> rules = rules(rooms.values().stream().flatMap(List::stream).toList(), window);
        Map<UUID, List<String>> photos = hotels.photos(hotelIds);
        Map<UUID, Rating> hotelRatings = ratings.forHotels(hotelIds);
        Map<UUID, HotelReliability> scores = reliability.forHotels(hotelIds);

        record Ranked(HotelResult result, int score) {
        }
        List<Ranked> ranked = new ArrayList<>();
        for (Candidate hotel : candidates) {
            PricingContext context = pricing.context(hotel.town(), window.nightOf(), hotel.roomsFree(),
                    hotel.totalRooms());
            List<RoomOption> options = options(rooms.getOrDefault(hotel.id(), List.of()), rules, window, guests,
                    hotel.roomsFree(), context);
            if (options.isEmpty()) {
                continue;
            }
            Rating rating = hotelRatings.getOrDefault(hotel.id(), Rating.NEW);
            HotelResult result = new HotelResult(hotel.id(), hotel.slug(), hotel.name(), hotel.town(), hotel.lat(),
                    hotel.lng(), photos.getOrDefault(hotel.id(), List.of()), hotel.amenities(),
                    Math.round(hotel.distanceMetres() / 10.0) / 100.0, hotel.roomsFree(), rating.value(),
                    rating.count(), options.getFirst().fromPrice(), options);
            HotelReliability r = scores.get(hotel.id());
            ranked.add(new Ranked(result, r == null ? HotelReliability.NEW_SCORE : r.score()));
        }
        ranked.sort(Comparator.<Ranked>comparingInt(r -> band(r.result().distanceKm()))
                .thenComparing(Comparator.comparingInt(Ranked::score).reversed())
                .thenComparingLong(r -> r.result().fromPrice())
                .thenComparingDouble(r -> r.result().distanceKm()));
        return ranked.stream().map(Ranked::result).toList();
    }

    /** Options at one hotel for the stay, cheapest first (rooms free and occupancy pricing as of now). */
    public List<RoomOption> roomOptions(Hotel hotel, StayWindow window, int guests, int roomsFree) {
        List<RoomType> rooms = roomTypes.forHotel(hotel.id());
        PricingContext context = pricing.context(hotel.town(), window.nightOf(), roomsFree, hotel.totalRooms());
        return options(rooms, rules(rooms, window), window, guests, roomsFree, context);
    }

    private Map<UUID, List<FloorRule>> rules(List<RoomType> rooms, StayWindow window) {
        return floorRules.forRoomTypes(rooms.stream().map(RoomType::id).toList(), window.slotType()).stream()
                .collect(Collectors.groupingBy(FloorRule::roomTypeId));
    }

    /**
     * Single rooms that fit the guests; when none does and two rooms are free, up to {@link #MAX_PAIRS} pairs of
     * rooms (the same type twice allowed) that sleep them together. Cheapest first.
     */
    private List<RoomOption> options(List<RoomType> rooms, Map<UUID, List<FloorRule>> rules, StayWindow window,
            int guests, int roomsFree, PricingContext context) {
        List<RoomOption> singles = rooms.stream()
                .filter(room -> room.maxGuests() >= guests)
                .map(room -> option(List.of(room), rules, window, context))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingLong(RoomOption::fromPrice))
                .toList();
        if (!singles.isEmpty() || roomsFree < 2) {
            return singles;
        }
        List<RoomOption> pairs = new ArrayList<>();
        List<RoomType> sorted = rooms.stream().sorted(Comparator.comparingLong(RoomType::listPrice)
                .thenComparing(r -> r.id().toString())).toList();
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i; j < sorted.size(); j++) {
                if (sorted.get(i).maxGuests() + sorted.get(j).maxGuests() >= guests) {
                    option(List.of(sorted.get(i), sorted.get(j)), rules, window, context).ifPresent(pairs::add);
                }
            }
        }
        return pairs.stream()
                .sorted(Comparator.comparingLong(RoomOption::fromPrice).thenComparingInt(RoomOption::maxGuests))
                .limit(MAX_PAIRS).toList();
    }

    /** The engine's opening price for these rooms together, or empty if a room has no floor rule. */
    private Optional<RoomOption> option(List<RoomType> rooms, Map<UUID, List<FloorRule>> rules, StayWindow window,
            PricingContext context) {
        return StayPricing.policy(rooms, rules, window, context).flatMap(policy -> {
            try {
                return Optional.of(new RoomOption(rooms.getFirst().id(), rooms.stream().map(RoomType::id).toList(),
                        rooms.size(), rooms.stream().map(RoomType::name).collect(Collectors.joining(" + ")),
                        rooms.stream().mapToInt(RoomType::maxGuests).sum(),
                        engine.openingOffer(policy, window.arrive().toLocalTime())));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping rooms {}: {}", rooms.stream().map(RoomType::id).toList(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    private List<Candidate> candidates(double lat, double lng, StayWindow window, List<SearchFilter> filters) {
        String[] amenities = filters.stream().map(SearchFilter::amenity).filter(a -> a != null).toArray(String[]::new);
        return jdbc.sql("""
                        SELECT h.id, h.slug, h.name, h.town, ST_Y(h.location::geometry) AS lat,
                               ST_X(h.location::geometry) AS lng, h.amenities, h.total_rooms,
                               rooms_available(h.id, :night, :now) AS rooms_free,
                               ST_Distance(h.location, p.g) AS distance_m
                        FROM hotel h
                        CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography AS g) p
                        WHERE h.active AND h.reliability_paused_at IS NULL
                          AND rooms_available(h.id, :night, :now) > 0
                          AND ST_DWithin(h.location, p.g, :radius)
                          AND h.amenities @> CAST(:amenities AS text[])
                          AND (NOT :coupleFriendly OR COALESCE((h.policies ->> 'couple_friendly')::boolean, false))
                          AND (NOT :localIdOk OR COALESCE((h.policies ->> 'local_id_ok')::boolean, false))""")
                .param("lat", lat).param("lng", lng).param("night", window.nightOf())
                .param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC))
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
                amenities == null ? List.of() : List.of((String[]) amenities.getArray()), rs.getInt("rooms_free"),
                rs.getInt("total_rooms"), rs.getDouble("distance_m"));
    }
}
