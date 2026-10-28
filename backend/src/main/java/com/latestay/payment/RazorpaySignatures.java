package com.latestay.payment;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Razorpay's two signatures, both lowercase hex HMAC-SHA256: Checkout signs {@code order_id|payment_id} with the
 * key secret; webhooks sign the raw request body with the webhook secret. Comparisons are constant-time.
 */
public final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    public static boolean checkoutValid(String orderId, String paymentId, String signature, String keySecret) {
        if (orderId == null || paymentId == null) {
            return false;
        }
        return matches(hmac(keySecret, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)), signature);
    }

    public static boolean webhookValid(byte[] body, String signature, String webhookSecret) {
        return body != null && matches(hmac(webhookSecret, body), signature);
    }

    public static String hmac(String secret, byte[] data) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("no secret configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean matches(String expected, String given) {
        if (given == null || given.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                given.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
}
