package com.latestay.whatsapp;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The callback URL set in the Meta app (WhatsApp > Configuration), subscribed to the "messages" field. */
@Hidden
@RestController
class WhatsAppWebhookController {

    private final WhatsAppWebhookService webhooks;

    WhatsAppWebhookController(WhatsAppWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping(path = "/api/webhooks/whatsapp", produces = MediaType.TEXT_PLAIN_VALUE)
    String verify(@RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        return webhooks.verify(mode, token, challenge);
    }

    @PostMapping("/api/webhooks/whatsapp")
    WhatsAppWebhookService.Result receive(@RequestBody byte[] body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        return webhooks.receive(body, signature);
    }

    @ExceptionHandler(WhatsAppWebhookService.InvalidSignatureException.class)
    ProblemDetail invalid(WhatsAppWebhookService.InvalidSignatureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(WhatsAppWebhookService.NotConfiguredException.class)
    ProblemDetail notConfigured(WhatsAppWebhookService.NotConfiguredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badBody(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
