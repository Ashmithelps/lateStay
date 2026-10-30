package com.latestay.dev.clock;

import com.latestay.stay.LateStayTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Local profile only: starts the backend's clock at {@code latestay.dev.clock-at} (India time, e.g.
 * 2026-10-29T23:45) and lets it tick from there, so night-time flows ("the hotel turned me away", rescue
 * searches) can be tried during the day. ProductionSafety refuses to start production with it set.
 */
@Configuration
@Profile("local")
@ConditionalOnProperty("latestay.dev.clock-at")
class DevClockConfig {

    private static final Logger log = LoggerFactory.getLogger(DevClockConfig.class);

    @Bean
    @Primary
    Clock devClock(@Value("${latestay.dev.clock-at}") String at) {
        Clock system = Clock.system(LateStayTime.ZONE);
        Duration offset = Duration.between(LocalDateTime.now(system), LocalDateTime.parse(at));
        log.warn("Development clock: starting at {} India time ({} from now)", at, offset);
        return Clock.offset(system, offset);
    }
}
