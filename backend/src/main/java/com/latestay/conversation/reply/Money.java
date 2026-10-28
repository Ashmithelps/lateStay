package com.latestay.conversation.reply;

import java.text.NumberFormat;
import java.util.Locale;

/** Formats rupees the way the UI does: ₹1,000 (Indian digit grouping). */
public final class Money {

    private static final Locale EN_IN = Locale.forLanguageTag("en-IN");

    private Money() {
    }

    public static String format(long rupees) {
        NumberFormat format = NumberFormat.getIntegerInstance(EN_IN);
        return "₹" + format.format(rupees);
    }
}
