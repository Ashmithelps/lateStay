package com.latestay.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PushProperties.class)
class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    PushGateway pushGateway(PushProperties properties) {
        if (properties.firebaseServiceAccountJson().isBlank()) {
            log.warn("FIREBASE_SERVICE_ACCOUNT_JSON not set: push notifications are queued but not sent");
            return new DisabledPushGateway();
        }
        return new FirebasePushGateway(properties.firebaseServiceAccountJson());
    }
}
