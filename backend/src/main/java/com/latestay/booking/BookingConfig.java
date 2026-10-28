package com.latestay.booking;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BookingProperties.class)
class BookingConfig {
}
