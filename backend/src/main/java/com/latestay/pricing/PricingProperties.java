package com.latestay.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Inputs to the engine's occupancy pricing ({@code latestay.pricing.*}).
 *
 * @param version 2 = occupancy, weekend and season pricing; 1 = the rules before it (a switch back, if ever needed)
 * @param weekendPercent the nights of Friday and Saturday open at this percentage (with any season), 100 = off
 */
@ConfigurationProperties("latestay.pricing")
public record PricingProperties(@DefaultValue("2") int version, @DefaultValue("110") int weekendPercent) {
}
