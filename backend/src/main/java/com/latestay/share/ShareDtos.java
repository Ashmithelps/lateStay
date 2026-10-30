package com.latestay.share;

import java.time.Instant;
import java.time.LocalDateTime;

public final class ShareDtos {

    private ShareDtos() {
    }

    /** The traveller's live link, shown on the pass. */
    public record ShareLink(String token, String url, Instant expiresAt) {
    }

    public enum SharedStatus {
        /** Paid; the hotel hasn't confirmed yet. */
        WAITING,
        CONFIRMED,
        CHECKED_IN,
        /** The stay is over. */
        FINISHED,
        /** Cancelled, declined, refunded, or anything else that means the guest isn't staying there. */
        ENDED
    }

    /**
     * What family sees on /stay/[token]: never the desk code, a phone number, a guest's name or any price.
     *
     * @param arriveAround the arrival time the traveller gave (India time)
     */
    public record SharedStay(String hotelName, String address, String town, double lat, double lng,
            LocalDateTime arriveAround, SharedStatus status, Instant expiresAt) {
    }
}
