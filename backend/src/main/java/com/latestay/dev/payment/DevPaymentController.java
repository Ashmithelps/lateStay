package com.latestay.dev.payment;

import com.latestay.payment.FakeRazorpayGateway;
import com.latestay.payment.PaymentsUnavailableException;
import com.latestay.payment.RazorpayGateway;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local profile, fake gateway only: plays Razorpay Checkout's part by handing back what a successful payment of the
 * order would (payment id and signature). The web app then verifies it through the normal API.
 */
@Profile("local")
@RestController
class DevPaymentController {

    private final RazorpayGateway gateway;

    DevPaymentController(RazorpayGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/api/dev/payments/{orderId}/pay")
    Map<String, String> pay(@PathVariable String orderId) {
        if (gateway.mode() != RazorpayGateway.Mode.FAKE) {
            throw new PaymentsUnavailableException();
        }
        String paymentId = "pay_fake_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        return Map.of("orderId", orderId, "paymentId", paymentId,
                "signature", FakeRazorpayGateway.sign(orderId, paymentId));
    }
}
