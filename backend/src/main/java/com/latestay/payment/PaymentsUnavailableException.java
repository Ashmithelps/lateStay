package com.latestay.payment;

/** No Razorpay keys are configured. Maps to 503. */
public class PaymentsUnavailableException extends RuntimeException {

    public PaymentsUnavailableException() {
        super("payments are not configured (set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET)");
    }
}
