package com.latestay.push;

import java.util.Map;

/**
 * What one notification carries: a title, one line of text, and where tapping it goes. Never a phone number, a desk
 * code, a guest's name or a price beyond the deposit.
 *
 * @param data bookingId and path ("/pass/<id>" or "/pass/<id>/review")
 */
public record PushPayload(String title, String body, Map<String, String> data) {

    public static final String TITLE = "LateStay";
    /** The Android notification channel the app creates ("Booking updates"). */
    public static final String ANDROID_CHANNEL = "bookings";

    public static PushPayload of(String bookingId, String body, String path) {
        return new PushPayload(TITLE, body, Map.of("bookingId", bookingId, "path", path));
    }
}
