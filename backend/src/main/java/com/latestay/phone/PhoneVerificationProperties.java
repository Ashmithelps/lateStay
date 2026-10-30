package com.latestay.phone;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phone verification by WhatsApp code ({@code latestay.phone-verification.*}).
 *
 * @param secret HMAC key for the stored hashes and the device cookie (PHONE_VERIFICATION_SECRET, 32+ characters)
 * @param cookieSecure send the cookie over https only (off for local http development)
 */
@ConfigurationProperties("latestay.phone-verification")
public record PhoneVerificationProperties(
        @DefaultValue("") String secret,
        @DefaultValue("5m") Duration codeValidity,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("3") int sendsPerPhonePer15Minutes,
        @DefaultValue("10") int sendsPerPhonePerDay,
        @DefaultValue("30d") Duration remember,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("phone_otp") String template) {
}
