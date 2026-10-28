package com.latestay.stay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.latestay.hotel.Json;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StayRequestRepository {

    private final JdbcClient jdbc;

    public StayRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public StayRequest insert(double lat, double lng, StayWindow window, int guests, List<SearchFilter> filters) {
        UUID id = jdbc.sql("""
                        INSERT INTO stay_request (location, arrive_at, leave_at, guests, filters)
                        VALUES (ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :arrive, :leave, :guests,
                                CAST(:filters AS jsonb))
                        RETURNING id""")
                .param("lat", lat).param("lng", lng)
                .param("arrive", window.arrive().atZone(LateStayTime.ZONE).toOffsetDateTime())
                .param("leave", window.leave().atZone(LateStayTime.ZONE).toOffsetDateTime())
                .param("guests", guests)
                .param("filters", Json.write(filters.stream().map(SearchFilter::code).toList()))
                .query(UUID.class).single();
        return new StayRequest(id, lat, lng, window, guests, filters);
    }

    public Optional<StayRequest> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng, arrive_at,
                               leave_at, guests, filters::text AS filters
                        FROM stay_request WHERE id = :id""")
                .param("id", id)
                .query((rs, n) -> new StayRequest(
                        rs.getObject("id", UUID.class),
                        rs.getDouble("lat"),
                        rs.getDouble("lng"),
                        StayWindow.of(
                                rs.getObject("arrive_at", OffsetDateTime.class).atZoneSameInstant(LateStayTime.ZONE)
                                        .toLocalDateTime(),
                                rs.getObject("leave_at", OffsetDateTime.class).atZoneSameInstant(LateStayTime.ZONE)
                                        .toLocalDateTime()),
                        rs.getInt("guests"),
                        Json.read(rs.getString("filters"), new TypeReference<List<String>>() {
                        }).stream().map(SearchFilter::parse).toList()))
                .optional();
    }
}
