package com.latestay.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.latestay.jobs.JobRuns;
import com.latestay.jobs.SchedulerLockConfig;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends recorded events to PostHog's batch API every 30 seconds, from the server: the traveller's browser never
 * talks to PostHog, so PostHog never sees their IP ({@code $ip} is blanked and GeoIP is off as well). Persons are
 * not created ({@code $process_person_profile: false}). Without POSTHOG_API_KEY nothing is sent.
 */
@Component
public class PostHogForwarder {

    private static final Logger log = LoggerFactory.getLogger(PostHogForwarder.class);
    private static final int BATCH = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Row(long id, String name, String distinctId, String town, String utmSource, String properties,
            OffsetDateTime occurredAt) {
    }

    private final JdbcClient jdbc;
    private final AnalyticsProperties properties;
    private final RestClient http;
    private final Clock clock;
    private final JobRuns jobRuns;

    PostHogForwarder(JdbcClient jdbc, AnalyticsProperties properties, RestClient.Builder builder, Clock clock,
            JobRuns jobRuns) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.http = builder.clone().baseUrl(properties.posthogHost()).build();
        this.clock = clock;
        this.jobRuns = jobRuns;
    }

    @Scheduled(fixedDelayString = "${latestay.analytics.forward-interval:30s}", initialDelay = 30_000)
    @SchedulerLock(name = SchedulerLockConfig.ANALYTICS_FORWARDER, lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void tick() {
        jobRuns.ran(SchedulerLockConfig.ANALYTICS_FORWARDER);
        if (!properties.forwards()) {
            return;
        }
        try {
            forward();
        } catch (RuntimeException e) {
            log.warn("PostHog forwarding failed, will retry: {}", e.getMessage());
        }
    }

    /** @return how many events were sent */
    public int forward() {
        List<Row> rows = jdbc.sql("""
                        SELECT id, name, distinct_id, town, utm_source, properties::text AS properties, occurred_at
                        FROM analytics_event WHERE forwarded_at IS NULL ORDER BY id LIMIT :limit""")
                .param("limit", BATCH)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("name"), rs.getString("distinct_id"),
                        rs.getString("town"), rs.getString("utm_source"), rs.getString("properties"),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
        if (rows.isEmpty()) {
            return 0;
        }
        http.post().uri("/batch/").contentType(MediaType.APPLICATION_JSON).body(batch(properties.posthogApiKey(), rows))
                .retrieve().toBodilessEntity();
        jdbc.sql("UPDATE analytics_event SET forwarded_at = :now WHERE id IN (:ids)")
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("ids", rows.stream().map(Row::id).toList()).update();
        return rows.size();
    }

    static String batch(String apiKey, List<Row> rows) {
        ObjectNode body = JSON.createObjectNode();
        body.put("api_key", apiKey);
        ArrayNode events = body.putArray("batch");
        for (Row r : rows) {
            ObjectNode e = events.addObject();
            e.put("event", r.name());
            e.put("distinct_id", r.distinctId());
            e.put("timestamp", r.occurredAt().toInstant().toString());
            e.put("uuid", java.util.UUID.nameUUIDFromBytes(("latestay-event-" + r.id()).getBytes()).toString());
            ObjectNode props = e.putObject("properties");
            try {
                props.setAll((ObjectNode) JSON.readTree(r.properties()));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            if (r.town() != null) {
                props.put("town", r.town());
            }
            if (r.utmSource() != null) {
                props.put("utm_source", r.utmSource());
            }
            props.putNull("$ip");
            props.put("$geoip_disable", true);
            props.put("$process_person_profile", false);
            props.put("$lib", "latestay-backend");
        }
        return body.toString();
    }
}
