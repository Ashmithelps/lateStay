package com.latestay.conversation.intent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IntentConfig {

    @Bean
    AmountExtractor amountExtractor() {
        return new AmountExtractor();
    }
}
