package com.latestay.promo;

/** A promo code that can't be used, with the reason to show the traveller (400). */
public class PromoRejectedException extends RuntimeException {

    private final String code;

    public PromoRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
