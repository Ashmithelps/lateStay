package com.latestay.payment;

/** The calls LateStay makes to Razorpay. Amounts are in rupees here and paise on the wire. */
public interface RazorpayGateway {

    enum Mode { RAZORPAY, FAKE }

    record Order(String id, long amount) {
    }

    /** @param processed Razorpay already reports the refund as processed (no need to wait for the webhook) */
    record Refund(String id, boolean processed) {
    }

    Mode mode();

    /** The public key id the web app passes to Checkout. */
    String keyId();

    Order createOrder(long amount, String receipt);

    /** Refunds a captured payment in full. Calling it again for a fully refunded payment returns that refund. */
    Refund refund(String paymentId, long amount, String receipt);

    boolean checkoutSignatureValid(String orderId, String paymentId, String signature);
}
