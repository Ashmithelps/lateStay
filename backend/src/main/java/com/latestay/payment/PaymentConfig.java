package com.latestay.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Real Razorpay with keys; the fake gateway without keys only where allowed; otherwise payments are off. */
@Configuration
@EnableConfigurationProperties(RazorpayProperties.class)
class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    @Bean
    RazorpayGateway razorpayGateway(RazorpayProperties properties, RestClient.Builder builder) {
        if (properties.hasKeys()) {
            return new HttpRazorpayGateway(properties, builder);
        }
        if (properties.allowFake()) {
            log.warn("RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET not set: using the fake payment gateway");
            return new FakeRazorpayGateway();
        }
        log.warn("RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET not set: payments disabled");
        return new DisabledRazorpayGateway();
    }

    static class DisabledRazorpayGateway implements RazorpayGateway {

        @Override
        public Mode mode() {
            return Mode.RAZORPAY;
        }

        @Override
        public String keyId() {
            throw new PaymentsUnavailableException();
        }

        @Override
        public Order createOrder(long amount, String receipt) {
            throw new PaymentsUnavailableException();
        }

        @Override
        public Refund refund(String paymentId, long amount, String receipt) {
            throw new PaymentsUnavailableException();
        }

        @Override
        public boolean checkoutSignatureValid(String orderId, String paymentId, String signature) {
            return false;
        }
    }
}
