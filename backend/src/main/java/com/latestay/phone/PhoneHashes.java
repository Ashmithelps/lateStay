package com.latestay.phone;

import com.latestay.payment.RazorpaySignatures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Keyed hashes (HMAC-SHA256 with PHONE_VERIFICATION_SECRET) for phone numbers, codes and IPs, so the database never
 * holds them in plain text, and for signing the "verified on this device" cookie.
 */
@Component
@EnableConfigurationProperties(PhoneVerificationProperties.class)
public class PhoneHashes {

    private static final Logger log = LoggerFactory.getLogger(PhoneHashes.class);
    /** Development only; ProductionSafety refuses to start production with it. */
    static final String DEV_SECRET = "latestay-local-development-phone-secret";

    private final String secret;

    public PhoneHashes(PhoneVerificationProperties properties) {
        if (properties.secret().isBlank()) {
            log.warn("PHONE_VERIFICATION_SECRET not set: using the development secret");
            this.secret = DEV_SECRET;
        } else {
            this.secret = properties.secret();
        }
    }

    /** @param e164 a normalised number, +91XXXXXXXXXX */
    public String phone(String e164) {
        return hmac("phone:" + e164);
    }

    String code(String verificationId, String code) {
        return hmac("code:" + verificationId + ":" + code);
    }

    String ip(String ip) {
        return hmac("ip:" + ip);
    }

    String sign(String value) {
        return hmac("cookie:" + value);
    }

    static boolean same(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private String hmac(String value) {
        return RazorpaySignatures.hmac(secret, value.getBytes(StandardCharsets.UTF_8));
    }
}
