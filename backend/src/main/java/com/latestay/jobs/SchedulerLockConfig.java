package com.latestay.jobs;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock over the database (table {@code shedlock}, V8): every {@code @Scheduled} job also carries a
 * {@code @SchedulerLock}, so two instances (a deploy's overlap, or more machines later) never run it twice. Lock
 * times come from the database's clock, not each machine's.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    /** Lock names: one per job. */
    public static final String BOOKING_JOBS = "booking-jobs";
    public static final String PHONE_RETENTION = "phone-retention";
    public static final String WHATSAPP_SENDER = "whatsapp-sender";
    public static final String NIGHTLY_PING = "nightly-ping";
    public static final String WEEKLY_SETTLEMENT = "weekly-settlement";
    public static final String OWNER_LINK_CLEANUP = "owner-link-cleanup";
    public static final String MORNING_DIGEST = "morning-digest";
    public static final String ANALYTICS_FORWARDER = "analytics-forwarder";
    public static final String REVIEW_INVITES = "review-invites";

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
