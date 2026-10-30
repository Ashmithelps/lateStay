package com.latestay.web;

import com.latestay.payment.RazorpayProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start production with development settings: the local profile (seed data, dev endpoints), the fake
 * payment gateway, a non-HTTPS web origin or link URL, weak secrets or a weak admin password. APP_ENV must say which
 * environment this is: production never seeds data, staging never takes live Razorpay keys.
 */
@Component
@Profile("prod")
class ProductionSafety {

    ProductionSafety(Environment environment, RazorpayProperties razorpay,
            @Value("${latestay.web-origin}") String webOrigin,
            @Value("${latestay.admin.password:}") String adminPassword,
            com.latestay.phone.PhoneVerificationProperties phone) {
        List<String> problems = new ArrayList<>();
        if (Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            problems.add("the local profile is active (seed data and dev endpoints)");
        }
        if (razorpay.allowFake()) {
            problems.add("latestay.razorpay.allow-fake is on");
        }
        if (!razorpay.hasKeys()) {
            problems.add("RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET are not set");
        }
        for (String origin : webOrigin.split(",")) {
            if (!origin.trim().startsWith("https://")) {
                problems.add("WEB_ORIGIN must be https (got " + origin.trim() + ")");
            }
        }
        if (!adminPassword.isEmpty() && adminPassword.length() < 16) {
            problems.add("ADMIN_PASSWORD must be at least 16 characters");
        }
        if (environment.containsProperty("latestay.dev.clock-at")) {
            problems.add("latestay.dev.clock-at is set (development clock)");
        }
        if (phone.secret().length() < 32) {
            problems.add("PHONE_VERIFICATION_SECRET must be at least 32 characters");
        }
        if (!phone.cookieSecure()) {
            problems.add("the phone cookie must be Secure in production");
        }
        String appEnv = environment.getProperty("latestay.environment", "local");
        boolean seed = environment.getProperty("latestay.seed.enabled", Boolean.class, false);
        boolean stagingProfile = Arrays.asList(environment.getActiveProfiles()).contains("staging");
        switch (appEnv) {
            case "production" -> {
                if (stagingProfile) {
                    problems.add("APP_ENV is production but the staging profile is active");
                }
                if (seed) {
                    problems.add("seed data is on (SEED_DATA); it only ever runs locally and on staging");
                }
            }
            case "staging" -> {
                if (razorpay.keyId().startsWith("rzp_live_")) {
                    problems.add("staging takes Razorpay test keys only (got a live key)");
                }
            }
            default -> problems.add("APP_ENV must be staging or production with the prod profile (got " + appEnv + ")");
        }
        String webUrl = environment.getProperty("latestay.public-web-url", "");
        if (!webUrl.startsWith("https://")) {
            problems.add("PUBLIC_WEB_URL must be https (got " + webUrl + ")");
        }
        if (environment.getProperty("latestay.owner.secret", "").length() < 32) {
            problems.add("OWNER_SESSION_SECRET must be at least 32 characters");
        }
        if (!environment.getProperty("latestay.owner.cookie-secure", Boolean.class, true)) {
            problems.add("the owner cookie must be Secure in production");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start in production: " + String.join("; ", problems));
        }
    }
}
