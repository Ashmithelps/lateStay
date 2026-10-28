package com.latestay.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * WhatsApp Business Cloud API settings ({@code latestay.whatsapp.*}, from WA_* env vars).
 *
 * @param phoneNumberId the business phone number's id (Meta dashboard, WhatsApp > API setup)
 * @param accessToken a system-user or temporary access token for the Graph API
 * @param verifyToken any string; Meta sends it back on webhook verification
 * @param appSecret the Meta app's secret; signs every webhook (X-Hub-Signature-256)
 * @param language the language code the templates were approved in
 */
@ConfigurationProperties("latestay.whatsapp")
public record WhatsAppProperties(
        @DefaultValue("") String phoneNumberId,
        @DefaultValue("") String accessToken,
        @DefaultValue("") String verifyToken,
        @DefaultValue("") String appSecret,
        @DefaultValue("v23.0") String graphVersion,
        @DefaultValue("https://graph.facebook.com") String baseUrl,
        @DefaultValue("en") String language,
        @DefaultValue("5") int maxAttempts) {

    /** Can send: Graph API credentials are set. */
    public boolean canSend() {
        return !phoneNumberId.isBlank() && !accessToken.isBlank();
    }
}
