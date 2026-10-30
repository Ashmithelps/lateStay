package com.latestay.analytics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The privacy gate every event passes. Only the event's whitelisted properties survive, and each value must be:
 * a slug-like tag (lower-case letters, digits, "-", "_", ".", at most 64 characters, no run of 4 or more digits,
 * so no phone number, desk code or verification code fits), a small number (0-50, e.g. guests or a round), or a
 * boolean. Names can't pass: they have capitals or spaces. Anything else is dropped and counted as rejected.
 */
public final class AnalyticsSanitizer {

    public record Result(Map<String, Object> properties, int rejected) {
    }

    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{4,}");
    private static final int MAX_NUMBER = 50;

    private AnalyticsSanitizer() {
    }

    public static Result clean(AnalyticsEventName event, Map<String, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        int rejected = 0;
        if (raw == null) {
            return new Result(out, 0);
        }
        for (var entry : raw.entrySet()) {
            Object value = entry.getValue();
            if (!event.properties().contains(entry.getKey()) || !allowed(value)) {
                rejected++;
                continue;
            }
            out.put(entry.getKey(), value);
        }
        return new Result(out, rejected);
    }

    static boolean allowed(Object value) {
        if (value instanceof Boolean) {
            return true;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && d >= 0 && d <= MAX_NUMBER;
        }
        return value instanceof String s && isTag(s);
    }

    /** A short lower-case tag with no run of 4+ digits (true for "pine-crest", false for "4821" or "Asha"). */
    public static boolean isTag(String s) {
        return s != null && TAG.matcher(s).matches() && !DIGIT_RUN.matcher(s).find();
    }

    /** ₹1,300 -> "1k-1.5k": a ₹500 band, so the exact price (which could look like a code) is never sent. */
    public static String priceBand(long rupees) {
        long low = Math.max(0, rupees) / 500 * 500;
        return thousands(low) + "-" + thousands(low + 500);
    }

    private static String thousands(long rupees) {
        return java.math.BigDecimal.valueOf(rupees, 3).stripTrailingZeros().toPlainString() + "k";
    }
}
