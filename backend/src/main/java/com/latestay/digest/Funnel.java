package com.latestay.digest;

import com.latestay.analytics.AnalyticsEventName;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * /admin/funnel: for the last N days, how many different visitors reached each step, optionally only for one town
 * or one UTM source; plus LLM spend per completed (checked in or completed) booking.
 */
@Service
public class Funnel {

    public record Step(String name, long visitors) {
    }

    /**
     * @param completedBookings bookings made in the window that reached CHECKED_IN or COMPLETED
     * @param llmCostPerBookingMicroUsd null when there are none
     */
    public record Report(int days, String town, String utmSource, List<Step> steps, long llmCostMicroUsd,
            long llmCostPaise, long completedBookings, Long llmCostPerBookingMicroUsd, Long llmCostPerBookingPaise,
            List<String> towns, List<String> sources) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final DigestService digests;

    Funnel(JdbcClient jdbc, Clock clock, DigestService digests) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.digests = digests;
    }

    public Report report(int days, String town, String utmSource) {
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(days));
        String t = blank(town);
        String u = blank(utmSource);
        List<Step> steps = AnalyticsEventName.FUNNEL.stream().map(e -> new Step(e.wireName(), jdbc.sql("""
                        SELECT count(DISTINCT distinct_id) FROM analytics_event
                        WHERE name = :name AND occurred_at >= :from AND occurred_at <= :to
                          AND (CAST(:town AS text) IS NULL OR town = :town)
                          AND (CAST(:utm AS text) IS NULL OR utm_source = :utm)""")
                .param("name", e.wireName()).param("from", from.atOffset(ZoneOffset.UTC))
                .param("to", to.atOffset(ZoneOffset.UTC)).param("town", t).param("utm", u)
                .query(Long.class).single())).toList();
        long cost = jdbc.sql("""
                        SELECT COALESCE(sum(t.llm_cost_micro_usd), 0) FROM negotiation_turn t
                        JOIN negotiation_session s ON s.id = t.session_id
                        JOIN stay_request q ON q.id = s.stay_request_id
                        WHERE t.created_at >= :from AND t.created_at <= :to
                          AND (CAST(:town AS text) IS NULL OR q.town = :town)
                          AND (CAST(:utm AS text) IS NULL OR q.utm_source = :utm)""")
                .param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC))
                .param("town", t).param("utm", u).query(Long.class).single();
        long completed = jdbc.sql("""
                        SELECT count(*) FROM booking b
                        JOIN negotiation_session s ON s.id = b.session_id
                        JOIN stay_request q ON q.id = s.stay_request_id
                        WHERE b.created_at >= :from AND b.created_at <= :to AND b.checked_in_at IS NOT NULL
                          AND b.status IN ('CHECKED_IN', 'COMPLETED')
                          AND (CAST(:town AS text) IS NULL OR q.town = :town)
                          AND (CAST(:utm AS text) IS NULL OR b.utm_source = :utm)""")
                .param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC))
                .param("town", t).param("utm", u).query(Long.class).single();
        List<String> towns = jdbc.sql("""
                        SELECT DISTINCT town FROM analytics_event WHERE town IS NOT NULL AND occurred_at >= :from
                        ORDER BY town""").param("from", from.atOffset(ZoneOffset.UTC)).query(String.class).list();
        List<String> sources = jdbc.sql("""
                        SELECT DISTINCT utm_source FROM analytics_event WHERE utm_source IS NOT NULL
                          AND occurred_at >= :from ORDER BY utm_source""")
                .param("from", from.atOffset(ZoneOffset.UTC)).query(String.class).list();
        Long perBooking = completed == 0 ? null : cost / completed;
        return new Report(days, t, u, steps, cost, digests.paise(cost), completed, perBooking,
                perBooking == null ? null : digests.paise(perBooking), towns, sources);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
