package com.latestay.review;

/** The only tags a review can carry. */
public enum ReviewTag {
    CLEAN("Clean"),
    WARM("Warm"),
    QUIET("Quiet"),
    FRIENDLY_DESK("Friendly desk"),
    AS_SHOWN("As shown");

    private final String label;

    ReviewTag(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
