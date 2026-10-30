package com.latestay.stay;

import java.util.regex.Pattern;

/**
 * Where a traveller came from: first-touch UTM tags (kept by the browser from the first visit) and the browser's
 * random analytics id. Anything that isn't a short tag is dropped, so free text (a name, a phone number) can't be
 * smuggled in.
 */
public record Attribution(String utmSource, String utmMedium, String utmCampaign, String analyticsId) {

    public static final Attribution NONE = new Attribution(null, null, null, null);

    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9._+-]{0,99}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern DIGITS = Pattern.compile("\\d{4,}");

    public static Attribution of(String utmSource, String utmMedium, String utmCampaign, String analyticsId) {
        return new Attribution(tag(utmSource), tag(utmMedium), tag(utmCampaign),
                analyticsId != null && ID.matcher(analyticsId).matches() ? analyticsId : null);
    }

    /** Lower-cased; null unless it looks like a tag ("instagram", "reel-manali-01") with no long digit runs. */
    public static String tag(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return TAG.matcher(v).matches() && !DIGITS.matcher(v).find() ? v : null;
    }
}
