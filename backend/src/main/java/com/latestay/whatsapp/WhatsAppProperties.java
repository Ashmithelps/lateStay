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
 * @param adminNumber the admin's WhatsApp number for alerts (ADMIN_WHATSAPP_NUMBER), digits with country code
 * @param supportNumber LateStay support's WhatsApp number (SUPPORT_WHATSAPP_NUMBER), linked from traveller messages
 * @param roomsTemplate booking_request_rooms is approved (WA_ROOMS_TEMPLATE): two-room bookings use it; until
 *     then they go out as booking_request, which says only the number of guests
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
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("") String adminNumber,
        @DefaultValue("") String supportNumber,
        @DefaultValue("false") boolean roomsTemplate) {

    /** wa.me link to support, or null when SUPPORT_WHATSAPP_NUMBER isn't set. */
    public String supportLink() {
        String digits = supportNumber.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : "https://wa.me/" + digits;
    }

    /** Can send: Graph API credentials are set. */
    public boolean canSend() {
        return !phoneNumberId.isBlank() && !accessToken.isBlank();
    }
}
