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

    /**
     * Tags a stay request made without them (the server-rendered first search) with the browser's UTM tags and
     * analytics id: first write wins, and only within an hour of the search (created_at is the database's time).
     */
    public boolean attributeIfUntagged(UUID id, Attribution a) {
        return jdbc.sql("""
                        UPDATE stay_request SET utm_source = :source, utm_medium = :medium, utm_campaign = :campaign,
                               analytics_id = :aid
                        WHERE id = :id AND analytics_id IS NULL AND utm_source IS NULL
                          AND created_at > now() - interval '1 hour'""")
                .param("id", id).param("source", a.utmSource()).param("medium", a.utmMedium())
                .param("campaign", a.utmCampaign()).param("aid", a.analyticsId())
                .update() == 1;
    }

    /** First-touch UTM tags, the analytics id and the search's town (the nearest result's). */
    public void attribute(UUID id, Attribution a, String town) {
        jdbc.sql("""
                        UPDATE stay_request SET utm_source = :source, utm_medium = :medium, utm_campaign = :campaign,
                               analytics_id = :aid, town = :town
                        WHERE id = :id""")
                .param("id", id).param("source", a.utmSource()).param("medium", a.utmMedium())
                .param("campaign", a.utmCampaign()).param("aid", a.analyticsId()).param("town", town).update();
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
