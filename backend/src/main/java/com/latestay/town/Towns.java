package com.latestay.town;

import com.latestay.hotel.NotFoundException;
import com.latestay.review.Rating;
import com.latestay.review.Ratings;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Town landing pages: every town with an active, unpaused hotel. Price ranges come only from prices travellers
 * actually agreed and paid for (one-room, late-night bookings in the last {@link #PRICE_WINDOW}), never from floors
 * or live offers, and only once there are {@link #MIN_BOOKINGS} of them.
 */
@Service
public class Towns {

    public static final Duration PRICE_WINDOW = Duration.ofDays(90);
    public static final int MIN_BOOKINGS = 3;
    static final int ROUND_TO = 50;

    /** Lower-case, letters and digits joined by dashes: "Old Manali" -> "old-manali". */
    static final String SLUG_SQL = "trim(both '-' from regexp_replace(lower(%s), '[^a-z0-9]+', '-', 'g'))";

    public record TownSummary(String slug, String name, int hotels) {
    }

    /** 25th to 75th percentile of agreed prices, rounded out to ₹50. */
    public record PriceRange(long low, long high, int bookings) {
    }

    public record TownHotel(String slug, String name, String address, @Schema(nullable = true) String photo,
            List<String> amenities, @Schema(nullable = true) java.math.BigDecimal rating, int reviewCount,
            @Schema(nullable = true) PriceRange priceRange) {
    }

    public record TownPage(String slug, String name, double lat, double lng, List<TownHotel> hotels,
            @Schema(nullable = true) PriceRange priceRange) {
    }

    private final JdbcClient jdbc;
    private final Ratings ratings;
    private final Clock clock;

    public Towns(JdbcClient jdbc, Ratings ratings, Clock clock) {
        this.jdbc = jdbc;
        this.ratings = ratings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TownSummary> list() {
        return jdbc.sql("""
                        SELECT %s AS slug, min(town) AS name, count(*) AS hotels FROM hotel
                        WHERE active AND reliability_paused_at IS NULL
                        GROUP BY 1 ORDER BY 2""".formatted(SLUG_SQL.formatted("town")))
                .query((rs, n) -> new TownSummary(rs.getString("slug"), rs.getString("name"), rs.getInt("hotels")))
                .list();
    }

    @Transactional(readOnly = true)
    public TownPage page(String slug) {
        record Row(UUID id, String slug, String name, String town, String address, String photo,
                List<String> amenities, double lat, double lng) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT h.id, h.slug, h.name, h.town, h.address, h.amenities,
                               ST_Y(h.location::geometry) AS lat, ST_X(h.location::geometry) AS lng,
                               (SELECT url FROM hotel_photo p WHERE p.hotel_id = h.id ORDER BY sort_order LIMIT 1)
                                   AS photo
                        FROM hotel h
                        WHERE h.active AND h.reliability_paused_at IS NULL AND %s = :slug
                        ORDER BY h.name""".formatted(SLUG_SQL.formatted("h.town")))
                .param("slug", slug)
                .query((rs, n) -> {
                    java.sql.Array a = rs.getArray("amenities");
                    return new Row(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                            rs.getString("town"), rs.getString("address"), rs.getString("photo"),
                            a == null ? List.of() : List.of((String[]) a.getArray()), rs.getDouble("lat"),
                            rs.getDouble("lng"));
                })
                .list();
        if (rows.isEmpty()) {
            throw new NotFoundException("town", slug);
        }
        List<UUID> ids = rows.stream().map(Row::id).toList();
        Map<UUID, PriceRange> ranges = hotelRanges(ids);
        Map<UUID, Rating> hotelRatings = ratings.forHotels(ids);
        List<TownHotel> hotels = rows.stream().map(r -> {
            Rating rating = hotelRatings.getOrDefault(r.id(), Rating.NEW);
            return new TownHotel(r.slug(), r.name(), r.address(), r.photo(), r.amenities(), rating.value(),
                    rating.count(), ranges.get(r.id()));
        }).toList();
        double lat = rows.stream().mapToDouble(Row::lat).average().orElseThrow();
        double lng = rows.stream().mapToDouble(Row::lng).average().orElseThrow();
        return new TownPage(slug, rows.getFirst().town(), lat, lng, hotels, townRange(ids));
    }

    private static final String AGREED = """
            FROM booking b JOIN negotiation_session s ON s.id = b.session_id
            WHERE b.hotel_id IN (:ids) AND b.rooms = 1 AND s.slot_type = 'LATE_NIGHT'
              AND b.paid_at IS NOT NULL AND b.paid_at > :since
              AND b.status IN ('DEPOSIT_PAID', 'HOTEL_CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW')""";

    private Map<UUID, PriceRange> hotelRanges(List<UUID> ids) {
        Map<UUID, PriceRange> ranges = new HashMap<>();
        jdbc.sql("""
                        SELECT b.hotel_id, count(*) AS n,
                               percentile_cont(0.25) WITHIN GROUP (ORDER BY b.agreed_price) AS low,
                               percentile_cont(0.75) WITHIN GROUP (ORDER BY b.agreed_price) AS high
                        """ + AGREED + " GROUP BY b.hotel_id")
                .param("ids", ids).param("since", clock.instant().minus(PRICE_WINDOW).atOffset(ZoneOffset.UTC))
                .query(rs -> {
                    PriceRange range = range(rs.getInt("n"), rs.getDouble("low"), rs.getDouble("high"));
                    if (range != null) {
                        ranges.put(rs.getObject("hotel_id", UUID.class), range);
                    }
                });
        return ranges;
    }

    private PriceRange townRange(List<UUID> ids) {
        return jdbc.sql("""
                        SELECT count(*) AS n,
                               percentile_cont(0.25) WITHIN GROUP (ORDER BY b.agreed_price) AS low,
                               percentile_cont(0.75) WITHIN GROUP (ORDER BY b.agreed_price) AS high
                        """ + AGREED)
                .param("ids", ids).param("since", clock.instant().minus(PRICE_WINDOW).atOffset(ZoneOffset.UTC))
                .query((rs, n) -> range(rs.getInt("n"), rs.getDouble("low"), rs.getDouble("high")))
                .optional().orElse(null);
    }

    /** Null with fewer than {@link #MIN_BOOKINGS} bookings; otherwise the quartiles rounded out to ₹50. */
    static PriceRange range(int bookings, double low, double high) {
        if (bookings < MIN_BOOKINGS) {
            return null;
        }
        long l = (long) Math.floor(low / ROUND_TO) * ROUND_TO;
        long h = (long) Math.ceil(high / ROUND_TO) * ROUND_TO;
        return new PriceRange(Math.max(ROUND_TO, l), Math.max(Math.max(ROUND_TO, l), h), bookings);
    }
}
