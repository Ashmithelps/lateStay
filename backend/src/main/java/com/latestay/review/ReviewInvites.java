package com.latestay.review;

import com.latestay.jobs.JobRuns;
import com.latestay.jobs.SchedulerLockConfig;
import com.latestay.whatsapp.WhatsAppMessages;
import com.latestay.whatsapp.WhatsAppOutbox;
import com.latestay.whatsapp.WhatsAppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 11:00 Asia/Kolkata: travellers who ticked "Send my booking pass and updates on WhatsApp" and whose stay ended in
 * the last 24 hours (CHECKED_IN or COMPLETED, not yet reviewed, number not yet deleted) get one booking_update
 * asking how it was, linking to the review form on their pass.
 */
@Component
public class ReviewInvites {

    private static final Logger log = LoggerFactory.getLogger(ReviewInvites.class);
    static final Duration LOOKBACK = Duration.ofHours(24);
    static final String DEDUPE = WhatsAppMessages.BOOKING_UPDATE + ":%s:REVIEW";

    private final JdbcClient jdbc;
    private final WhatsAppOutbox outbox;
    private final WhatsAppProperties properties;
    private final JobRuns jobRuns;
    private final Clock clock;
    private final String webUrl;

    ReviewInvites(JdbcClient jdbc, WhatsAppOutbox outbox, WhatsAppProperties properties, JobRuns jobRuns, Clock clock,
            @Value("${latestay.public-web-url}") String webUrl) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.properties = properties;
        this.jobRuns = jobRuns;
        this.clock = clock;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    @Scheduled(cron = "${latestay.jobs.review-cron:0 0 11 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = SchedulerLockConfig.REVIEW_INVITES, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void morning() {
        jobRuns.ran(SchedulerLockConfig.REVIEW_INVITES);
        try {
            send();
        } catch (RuntimeException e) {
            log.error("Review invites failed", e);
        }
    }

    record Due(UUID bookingId, String phone, String hotelName) {
    }

    /** @return how many invites were queued (each booking at most once) */
    @Transactional
    public int send() {
        Instant now = clock.instant();
        var due = jdbc.sql("""
                        SELECT b.id, b.phone, h.name FROM booking b JOIN hotel h ON h.id = b.hotel_id
                        WHERE b.status IN ('CHECKED_IN', 'COMPLETED') AND b.whatsapp_updates_at IS NOT NULL
                          AND b.phone IS NOT NULL AND b.leave_at <= :now AND b.leave_at > :since
                          AND NOT EXISTS (SELECT 1 FROM review r WHERE r.booking_id = b.id)""")
                .param("now", now.atOffset(ZoneOffset.UTC)).param("since", now.minus(LOOKBACK).atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getString("phone"), rs.getString("name")))
                .list();
        int queued = 0;
        for (Due d : due) {
            boolean added = outbox.queue(null, WhatsAppMessages.bookingUpdate(WhatsAppOutbox.waId(d.phone()),
                            properties.language(), line(d.hotelName()), webUrl + "/pass/" + d.bookingId() + "/review"),
                    d.bookingId(), DEDUPE.formatted(d.bookingId()), WhatsAppOutbox.Audience.TRAVELLER);
            queued += added ? 1 : 0;
        }
        return queued;
    }

    /** The booking_update line ({{1}}: no full stop). */
    static String line(String hotelName) {
        return "how was your stay at " + hotelName + "? You can rate it in a few taps from your pass";
    }
}
