package com.latestay.booking;

import java.util.Optional;
import java.util.regex.Pattern;

/** Indian mobile numbers: 10 digits starting 6-9, optionally written with +91, 91 or 0 and spaces or dashes. */
public final class IndianMobile {

    private static final Pattern MOBILE = Pattern.compile("(?:\\+?91|0)?([6-9][0-9]{9})");

    private IndianMobile() {
    }

    /** The number as {@code +91XXXXXXXXXX}, or empty if it isn't an Indian mobile number. */
    public static Optional<String> normalise(String input) {
        if (input == null) {
            return Optional.empty();
        }
        var m = MOBILE.matcher(input.replaceAll("[\\s-]", ""));
        return m.matches() ? Optional.of("+91" + m.group(1)) : Optional.empty();
    }
}
