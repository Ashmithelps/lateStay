package com.latestay.push;

/** Notification texts used outside this package. */
public final class PushTexts {

    private PushTexts() {
    }

    /** The 11:00 review request. */
    public static String review(String hotel) {
        return PushNotifications.reviewText(hotel);
    }
}
