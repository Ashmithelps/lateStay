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

    static com.latestay.phone.PhoneVerificationProperties phone(String secret, boolean secure) {
        return new com.latestay.phone.PhoneVerificationProperties(secret, java.time.Duration.ofMinutes(5), 3, 3, 10,
                java.time.Duration.ofDays(30), secure, "phone_otp");
    }

    static final com.latestay.phone.PhoneVerificationProperties GOOD_PHONE =
            phone("0123456789abcdef0123456789abcdef0123", true);

    static MockEnvironment profiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("latestay.environment", "production")
                .withProperty("latestay.public-web-url", "https://latestay.in")
                .withProperty("latestay.owner.secret", "fedcba9876543210fedcba9876543210ff");
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void aProperProductionSetupStarts() {
        assertThatCode(() -> new ProductionSafety(profiles("prod"), keys(false), "https://latestay.in",
                "a-long-admin-password-here", GOOD_PHONE)).doesNotThrowAnyException();
    }

    @Test
    void refusesDevelopmentSettings() {
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod", "local"), keys(false), "https://latestay.in", "", GOOD_PHONE))
                .hasMessageContaining("local profile");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"), keys(true), "https://latestay.in", "", GOOD_PHONE))
                .hasMessageContaining("allow-fake");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"),
                new RazorpayProperties("", "", "", "https://api.razorpay.com/v1", false, false), "https://latestay.in", "", GOOD_PHONE))
                .hasMessageContaining("RAZORPAY_KEY_ID");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"), keys(false),
                "https://latestay.in, http://preview.example", "", GOOD_PHONE))
                .hasMessageContaining("WEB_ORIGIN must be https (got http://preview.example)");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"), keys(false), "https://latestay.in", "admin123", GOOD_PHONE))
                .hasMessageContaining("ADMIN_PASSWORD");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"), keys(false), "https://latestay.in", "",
                phone("short", true))).hasMessageContaining("PHONE_VERIFICATION_SECRET");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod"), keys(false), "https://latestay.in", "",
                phone("0123456789abcdef0123456789abcdef0123", false))).hasMessageContaining("Secure");
    }

    @Test
    void productionNeverSeedsAndStagingNeverTakesLiveKeys() {
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod").withProperty("latestay.seed.enabled", "true"),
                keys(false), "https://latestay.in", "", GOOD_PHONE)).hasMessageContaining("seed data is on");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod", "staging"), keys(false), "https://latestay.in", "",
                GOOD_PHONE)).hasMessageContaining("staging profile is active");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod").withProperty("latestay.environment", "local"),
                keys(false), "https://latestay.in", "", GOOD_PHONE)).hasMessageContaining("APP_ENV must be");

        var live = new RazorpayProperties("rzp_live_abc", "secret", "whsec", "https://api.razorpay.com/v1", false, true);
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod", "staging")
                .withProperty("latestay.environment", "staging").withProperty("latestay.seed.enabled", "true"),
                live, "https://staging.latestay.in", "", GOOD_PHONE)).hasMessageContaining("test keys only");
        assertThatCode(() -> new ProductionSafety(profiles("prod", "staging")
                .withProperty("latestay.environment", "staging").withProperty("latestay.seed.enabled", "true"),
                keys(false), "https://staging.latestay.in", "a-long-admin-password-here", GOOD_PHONE))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesWeakOwnerSignInAndPlainHttpLinks() {
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod").withProperty("latestay.owner.secret", "short"),
                keys(false), "https://latestay.in", "", GOOD_PHONE)).hasMessageContaining("OWNER_SESSION_SECRET");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod").withProperty("latestay.owner.cookie-secure", "false"),
                keys(false), "https://latestay.in", "", GOOD_PHONE)).hasMessageContaining("owner cookie must be Secure");
        assertThatThrownBy(() -> new ProductionSafety(profiles("prod").withProperty("latestay.public-web-url", "http://x"),
                keys(false), "https://latestay.in", "", GOOD_PHONE)).hasMessageContaining("PUBLIC_WEB_URL");
    }
}
