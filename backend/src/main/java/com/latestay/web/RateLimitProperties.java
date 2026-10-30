package com.latestay.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-minute limits (per hour for verification codes) ({@code latestay.rate-limit.*}). Buckets live in memory, so they're per backend instance:
 * LateStay runs one.
 */
@ConfigurationProperties("latestay.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") long messagesPerSession,
        @DefaultValue("20") long messagesPerIp,
        @DefaultValue("30") long negotiationsPerIp,
        @DefaultValue("10") long bookingsPerIp,
        @DefaultValue("10") long codesPerIpPerHour,
        @DefaultValue("30") long codeChecksPerIpPerHour,
        @DefaultValue("20") long ownerSignInsPerIpPerHour,
        @DefaultValue("120") long eventsPerIp) {
}
