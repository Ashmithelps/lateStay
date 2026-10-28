package com.latestay.whatsapp;

import com.latestay.payment.RazorpaySignatures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Meta signs each webhook body with the app secret: {@code X-Hub-Signature-256: sha256=<lowercase hex HMAC>}. */
public final class WhatsAppSignatures {

    private static final String PREFIX = "sha256=";

    private WhatsAppSignatures() {
    }

    public static boolean valid(byte[] body, String header, String appSecret) {
        if (body == null || header == null || !header.startsWith(PREFIX) || appSecret == null || appSecret.isEmpty()) {
            return false;
        }
        String expected = sign(body, appSecret);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                header.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /** The header value Meta would send for this body. */
    public static String sign(byte[] body, String appSecret) {
        return PREFIX + RazorpaySignatures.hmac(appSecret, body);
    }
}
