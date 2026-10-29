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
 * payment gateway, a non-HTTPS web origin or a weak admin password.
 */
@Component
@Profile("prod")
class ProductionSafety {

    ProductionSafety(Environment environment, RazorpayProperties razorpay,
            @Value("${latestay.web-origin}") String webOrigin,
            @Value("${latestay.admin.password:}") String adminPassword) {
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
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start in production: " + String.join("; ", problems));
        }
    }
}
