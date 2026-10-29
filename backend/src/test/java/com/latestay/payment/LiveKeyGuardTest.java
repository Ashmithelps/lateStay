package com.latestay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Razorpay stays in test mode until live mode is switched on deliberately (RAZORPAY_ALLOW_LIVE=true). */
class LiveKeyGuardTest {

    static RazorpayProperties props(String keyId, boolean allowLive) {
        return new RazorpayProperties(keyId, "secret", "", "https://api.razorpay.com/v1", false, allowLive);
    }

    @Test
    void liveKeysAreRefusedUnlessAllowed() {
        assertThatThrownBy(() -> new PaymentConfig().razorpayGateway(props("rzp_live_abc", false), RestClient.builder()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAZORPAY_ALLOW_LIVE");
        assertThat(new PaymentConfig().razorpayGateway(props("rzp_live_abc", true), RestClient.builder()).mode())
                .isEqualTo(RazorpayGateway.Mode.RAZORPAY);
        assertThat(new PaymentConfig().razorpayGateway(props("rzp_test_abc", false), RestClient.builder()).mode())
                .isEqualTo(RazorpayGateway.Mode.RAZORPAY);
    }
}
