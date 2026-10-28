package com.latestay.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Razorpay test or live keys ({@code latestay.razorpay.*}, from RAZORPAY_* env vars).
 *
 * @param allowFake with no keys, use {@link FakeRazorpayGateway} instead of disabling payments (local and tests only)
 */
@ConfigurationProperties("latestay.razorpay")
public record RazorpayProperties(
        @DefaultValue("") String keyId,
        @DefaultValue("") String keySecret,
        @DefaultValue("") String webhookSecret,
        @DefaultValue("https://api.razorpay.com/v1") String baseUrl,
        @DefaultValue("false") boolean allowFake) {

    public boolean hasKeys() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }
}
