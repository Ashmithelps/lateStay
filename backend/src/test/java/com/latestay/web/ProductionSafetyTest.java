package com.latestay.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latestay.payment.RazorpayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyTest {

    static RazorpayProperties keys(boolean fake) {
        return new RazorpayProperties("rzp_test_abc", "secret", "whsec", "https://api.razorpay.com/v1", fake, false);
    }

    static MockEnvironment profiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void aProperProductionSetupStarts() {
        assertThatCode(() -> new ProductionSafety(profiles("prod"), keys(false), "https://latestay.in",
                "a-long-admin-password-here")).doesNotThrowAnyException();
    }

    @Test
    void refusesDevelopmentSettings() {
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod", "local"), keys(false), "https://latestay.in", ""))
                .hasMessageContaining("local profile");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"), keys(true), "https://latestay.in", ""))
                .hasMessageContaining("allow-fake");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"),
                new RazorpayProperties("", "", "", "https://api.razorpay.com/v1", false, false), "https://latestay.in", ""))
                .hasMessageContaining("RAZORPAY_KEY_ID");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"), keys(false),
                "https://latestay.in, http://preview.example", ""))
                .hasMessageContaining("WEB_ORIGIN must be https (got http://preview.example)");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"), keys(false), "https://latestay.in", "admin123"))
                .hasMessageContaining("ADMIN_PASSWORD");
    }
}
