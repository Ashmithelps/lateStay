package com.latestay.payment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stands in for Razorpay when no keys are configured and {@code latestay.razorpay.allow-fake} is on (local
 * profile and tests). Orders and refunds are made up; Checkout signatures use {@link #KEY_SECRET} so the normal
 * verification path still runs.
 */
public class FakeRazorpayGateway implements RazorpayGateway {

    public static final String KEY_ID = "rzp_fake_local";
    public static final String KEY_SECRET = "fake_key_secret";

    @Override
    public Mode mode() {
        return Mode.FAKE;
    }

    @Override
    public String keyId() {
        return KEY_ID;
    }

    @Override
    public Order createOrder(long amount, String receipt) {
        return new Order("order_fake_" + shortId(), amount);
    }

    @Override
    public Refund refund(String paymentId, long amount, String receipt) {
        return new Refund("rfnd_fake_" + shortId(), true);
    }

    @Override
    public boolean checkoutSignatureValid(String orderId, String paymentId, String signature) {
        return RazorpaySignatures.checkoutValid(orderId, paymentId, signature, KEY_SECRET);
    }

    /** What Checkout would hand back for a successful fake payment of this order. */
    public static String sign(String orderId, String paymentId) {
        return RazorpaySignatures.hmac(KEY_SECRET, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}
