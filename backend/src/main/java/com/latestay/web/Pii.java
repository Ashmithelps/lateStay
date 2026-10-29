package com.latestay.web;

import java.util.regex.Pattern;

/** Keeps phone numbers out of logs and error reports: only the last four digits survive. */
public final class Pii {

    /** Indian mobiles in any common spelling (+91 98765 43210, 919876543210, 09876543210, 98765-43210). */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?91[\\s-]?|0)?[6-9]\\d{4}[\\s-]?\\d{5}(?!\\d)");

    private Pii() {
    }

    /** "+919876543210" -> "+91•••••3210"; anything not phone-shaped is returned as "•••". */
    public static String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 8) {
            return "•••";
        }
        String last4 = digits.substring(digits.length() - 4);
        return (digits.length() == 12 && digits.startsWith("91") ? "+91" : "") + "•••••" + last4;
    }

    /** Masks every phone number found in free text (log messages, exception messages, Sentry events). */
    public static String scrub(String text) {
        if (text == null) {
            return null;
        }
        return PHONE.matcher(text).replaceAll(m -> mask(m.group()));
    }
}
