package com.latestay.hotel;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AvailabilityRepository {

    private final JdbcClient jdbc;

    public AvailabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The latest report for a hotel and night replaces the previous one. */
    public void upsert(UUID hotelId, LocalDate nightOf, int roomsFree, AvailabilitySource source) {
        jdbc.sql("""
                        INSERT INTO nightly_availability (hotel_id, night_of, rooms_free, reported_at, source)
                        VALUES (:hotel, :night, :rooms, now(), :source)
                        ON CONFLICT (hotel_id, night_of)
                        DO UPDATE SET rooms_free = EXCLUDED.rooms_free, reported_at = now(), source = EXCLUDED.source""")
                .param("hotel", hotelId).param("night", nightOf).param("rooms", roomsFree)
                .param("source", source.name()).update();
    }

    /** 0 when nothing was reported for that night. */
    public int roomsFree(UUID hotelId, LocalDate nightOf) {
        return jdbc.sql("SELECT rooms_free FROM nightly_availability WHERE hotel_id = :hotel AND night_of = :night")
                .param("hotel", hotelId).param("night", nightOf).query(Integer.class).optional().orElse(0);
    }
}
