package com.latestay.review;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Ratings from reviews (every review's stars count, hidden text or not). */
@Component
public class Ratings {

    private final JdbcClient jdbc;

    public Ratings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Rating of(UUID hotelId) {
        return forHotels(java.util.List.of(hotelId)).getOrDefault(hotelId, Rating.NEW);
    }

    /** Hotels without reviews are missing from the map (use {@link Rating#NEW}). */
    public Map<UUID, Rating> forHotels(Collection<UUID> hotelIds) {
        Map<UUID, Rating> ratings = new HashMap<>();
        if (hotelIds.isEmpty()) {
            return ratings;
        }
        jdbc.sql("SELECT hotel_id, count(*) AS n, sum(stars) AS total FROM review WHERE hotel_id IN (:ids) "
                        + "GROUP BY hotel_id")
                .param("ids", hotelIds)
                .query(rs -> {
                    ratings.put(rs.getObject("hotel_id", UUID.class), Rating.of(rs.getInt("n"), rs.getLong("total")));
                });
        return ratings;
    }
}
