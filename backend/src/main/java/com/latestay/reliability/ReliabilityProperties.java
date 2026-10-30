package com.latestay.reliability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * When a hotel is paused from search ({@code latestay.reliability.*}).
 *
 * @param window how far back the numbers look
 * @param minDecisions confirm rate only counts once the hotel has answered (or failed to answer) this many bookings
 * @param minConfirmRatePercent pause below this confirm rate
 * @param maxStrikes pause at this many rescues plus upheld disputes
 * @param slowMinutes a median time to confirm above this costs 5 points
 */
@ConfigurationProperties("latestay.reliability")
public record ReliabilityProperties(
        @DefaultValue("30d") Duration window,
        @DefaultValue("3") int minDecisions,
        @DefaultValue("70") int minConfirmRatePercent,
        @DefaultValue("2") int maxStrikes,
        @DefaultValue("3") int slowMinutes) {
}
