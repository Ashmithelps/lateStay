package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(WhatsAppProperties.class)
class WhatsAppConfig {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppConfig.class);

    @Bean
    WhatsAppClient whatsAppClient(WhatsAppProperties properties, RestClient.Builder builder) {
        if (properties.canSend()) {
            return new GraphWhatsAppClient(properties, builder);
        }
        log.warn("WA_PHONE_NUMBER_ID / WA_ACCESS_TOKEN not set: WhatsApp messages are logged but not sent");
        return new WhatsAppClient() {
            @Override
            public boolean canSend() {
                return false;
            }

            @Override
            public String send(JsonNode message) {
                throw new WhatsAppSendException("WhatsApp is not configured");
            }
        };
    }
}
