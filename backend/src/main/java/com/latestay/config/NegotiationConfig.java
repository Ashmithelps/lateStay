package com.latestay.config;

import com.latestay.negotiation.NegotiationEngine;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NegotiationConfig {

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Kolkata"));
    }

    @Bean
    NegotiationEngine negotiationEngine(Clock clock) {
        return new NegotiationEngine(clock);
    }
}
