package com.latestay.analytics;

import java.util.Set;

/**
 * Every event LateStay counts, who sends it, and the only properties it may carry. Nothing personal is ever
 * allowed: no names, phone numbers, desk codes or verification codes (see {@link AnalyticsSanitizer}).
 */
public enum AnalyticsEventName {
    ROOM_NOW_TAPPED(true, Set.of("located")),
    SEARCH_STARTED(true, Set.of("place", "guests", "room_now")),
    RESULTS_VIEWED(true, Set.of("results", "room_now")),
    HOTEL_OPENED(true, Set.of("hotel")),
    HAGGLE_STARTED(true, Set.of("hotel", "room")),
    COUNTER_SENT(true, Set.of("hotel", "round")),
    OFFER_ACCEPTED(true, Set.of("hotel", "price_band")),
    CODE_SENT(true, Set.of()),
    CODE_VERIFIED(true, Set.of()),
    PAYMENT_STARTED(true, Set.of("hotel", "promo")),
    PAYMENT_SUCCEEDED(false, Set.of("hotel", "price_band", "promo")),
    HOTEL_CONFIRMED(false, Set.of("hotel")),
    CHECKED_IN(false, Set.of("hotel")),
    CANCELLED(false, Set.of("hotel", "stage")),
    TURNED_AWAY(false, Set.of("hotel")),
    RESCUED(false, Set.of("hotel"));

    /** The funnel on /admin/funnel, in order. */
    public static final java.util.List<AnalyticsEventName> FUNNEL = java.util.List.of(SEARCH_STARTED, RESULTS_VIEWED,
            HOTEL_OPENED, HAGGLE_STARTED, OFFER_ACCEPTED, CODE_SENT, CODE_VERIFIED, PAYMENT_STARTED, PAYMENT_SUCCEEDED,
            HOTEL_CONFIRMED, CHECKED_IN);

    private final boolean fromWeb;
    private final Set<String> properties;

    AnalyticsEventName(boolean fromWeb, Set<String> properties) {
        this.fromWeb = fromWeb;
        this.properties = properties;
    }

    /** Sent by the web app through POST /api/events (the rest only by the backend itself). */
    public boolean fromWeb() {
        return fromWeb;
    }

    public Set<String> properties() {
        return properties;
    }

    /** "room_now_tapped" */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static java.util.Optional<AnalyticsEventName> fromWire(String name) {
        for (AnalyticsEventName e : values()) {
            if (e.wireName().equals(name)) {
                return java.util.Optional.of(e);
            }
        }
        return java.util.Optional.empty();
    }
}
