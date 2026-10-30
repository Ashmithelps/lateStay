package com.latestay.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PostHog forwarding ({@code latestay.analytics.*}). Events are always kept in analytics_event (for /admin/funnel);
 * with an API key they are also sent to PostHog, from the server, without the traveller's IP.
 *
 * @param posthogApiKey the PostHog project API key (POSTHOG_API_KEY); blank = not forwarded
 * @param posthogHost the PostHog ingestion host (POSTHOG_HOST), EU cloud by default
 */
@ConfigurationProperties("latestay.analytics")
public record AnalyticsProperties(
        @DefaultValue("") String posthogApiKey,
        @DefaultValue("https://eu.i.posthog.com") String posthogHost) {

    public boolean forwards() {
        return !posthogApiKey.isBlank();
    }
}
