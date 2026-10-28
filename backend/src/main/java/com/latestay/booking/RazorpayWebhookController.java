package com.latestay.booking;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Razorpay calls this (configure it in the Razorpay dashboard with RAZORPAY_WEBHOOK_SECRET). */
@Hidden
@RestController
class RazorpayWebhookController {

    private final RazorpayWebhookService webhooks;

    RazorpayWebhookController(RazorpayWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/api/webhooks/razorpay")
    Map<String, String> receive(@RequestBody byte[] body,
            @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId) {
        return Map.of("outcome", webhooks.handle(body, signature, eventId).name());
    }
}
