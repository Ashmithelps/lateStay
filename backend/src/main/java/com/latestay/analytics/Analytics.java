package com.latestay.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.stay.Attribution;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Records funnel events in analytics_event (forwarded to PostHog by {@link PostHogForwarder}). Properties pass
 * {@link AnalyticsSanitizer}; the town and UTM source come from the event's stay request when it has one.
 */
@Service
@EnableConfigurationProperties(AnalyticsProperties.class)
public class Analytics {

    private static final Logger log = LoggerFactory.getLogger(Analytics.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final Clock clock;

    public Analytics(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param distinctId the browser's random analytics id (or "stay:<id>" when there is none)
     * @param utmSource first-touch source, used when there is no stay request to take it from
     */
    public void record(AnalyticsEventName event, String distinctId, UUID stayRequestId, String utmSource,
            Map<String, ?> properties) {
        AnalyticsSanitizer.Result clean = AnalyticsSanitizer.clean(event, properties);
        if (clean.rejected() > 0) {
            log.warn("Analytics event {}: {} property(ies) dropped by the privacy filter", event.wireName(),
                    clean.rejected());
        }
        String props;
        try {
            props = JSON.writeValueAsString(clean.properties());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.sql("""
                        INSERT INTO analytics_event (name, distinct_id, stay_request_id, town, utm_source, properties,
                                                     occurred_at)
                        SELECT :name, :distinct, :stay,
                               (SELECT town FROM stay_request WHERE id = :stay),
                               COALESCE((SELECT utm_source FROM stay_request WHERE id = :stay), :utm),
                               CAST(:props AS jsonb), :now""")
                .param("name", event.wireName()).param("distinct", distinctId).param("stay", stayRequestId)
                .param("utm", Attribution.tag(utmSource)).param("props", props)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
    }

    public UUID stayOfNegotiation(UUID negotiationId) {
        return jdbc.sql("SELECT stay_request_id FROM negotiation_session WHERE id = :id").param("id", negotiationId)
                .query(UUID.class).optional().orElse(null);
    }

    /** The browser's analytics id for a stay request, or a stand-in made from the stay request's id. */
    public String distinctIdFor(UUID stayRequestId) {
        return jdbc.sql("SELECT analytics_id FROM stay_request WHERE id = :id").param("id", stayRequestId)
                .query(String.class).optional().filter(id -> id != null && !id.isBlank())
                .orElse("stay:" + stayRequestId);
    }
}
