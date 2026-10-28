package com.latestay.booking;

import com.latestay.payment.FakeRazorpayGateway;
import com.latestay.payment.PaymentGatewayException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** The fake gateway, recording refund calls; can fail the next refunds or leave them pending. */
public class RecordingRazorpayGateway extends FakeRazorpayGateway {

    public final List<String> refundedPayments = new CopyOnWriteArrayList<>();
    public final AtomicInteger failNextRefunds = new AtomicInteger();
    public volatile boolean refundsProcessImmediately = true;

    @Override
    public Refund refund(String paymentId, long amount, String receipt) {
        if (failNextRefunds.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new PaymentGatewayException("Razorpay refund failed: 502 Bad Gateway");
        }
        refundedPayments.add(paymentId);
        return new Refund("rfnd_test_" + refundedPayments.size() + "_" + paymentId, refundsProcessImmediately);
    }

    /** Refund calls for one payment (other tests' payments share the database and the refund job). */
    public int refundCalls(String paymentId) {
        return java.util.Collections.frequency(refundedPayments, paymentId);
    }

    public void reset() {
        refundedPayments.clear();
        failNextRefunds.set(0);
        refundsProcessImmediately = true;
    }
}
