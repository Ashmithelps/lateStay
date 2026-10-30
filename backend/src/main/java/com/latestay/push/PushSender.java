package com.latestay.push;

import com.latestay.jobs.JobRuns;
import com.latestay.jobs.SchedulerLockConfig;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends queued notifications to every device registered for the booking. A message is SENT once at least one device
 * got it; tokens FCM rejects as gone are deleted; other failures are retried up to maxAttempts.
 */
@Component
public class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);
    static final int BATCH = 20;

    private record Queued(long id, UUID bookingId, String body, String path, int attempts) {
    }

    private final JdbcClient jdbc;
    private final Devices devices;
    private final PushGateway gateway;
    private final PushProperties properties;
    private final JobRuns jobRuns;
    private final Clock clock;

    public PushSender(JdbcClient jdbc, Devices devices, PushGateway gateway, PushProperties properties,
            JobRuns jobRuns, Clock clock) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.gateway = gateway;
        this.properties = properties;
        this.jobRuns = jobRuns;
        this.clock = clock;
    }

    /** @return how many messages reached at least one device */
    @Transactional
    public int sendQueued() {
        List<Queued> queued = jdbc.sql("""
                        SELECT id, booking_id, body, path, attempts FROM push_message WHERE status = 'QUEUED'
                        ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED""")
                .param("limit", BATCH)
                .query((rs, n) -> new Queued(rs.getLong("id"), rs.getObject("booking_id", UUID.class),
                        rs.getString("body"), rs.getString("path"), rs.getInt("attempts")))
                .list();
        int sent = 0;
        for (Queued m : queued) {
            List<String> tokens = devices.tokensFor(m.bookingId());
            if (!gateway.canSend() || tokens.isEmpty()) {
                finish(m.id(), "NOT_SENT", gateway.canSend() ? "no devices" : "push not configured");
                continue;
            }
            PushPayload payload = PushPayload.of(m.bookingId().toString(), m.body(), m.path());
            boolean delivered = false;
            boolean failed = false;
            for (String token : tokens) {
                switch (gateway.send(token, payload)) {
                    case SENT -> delivered = true;
                    case INVALID_TOKEN -> devices.forget(token);
                    case FAILED -> failed = true;
                }
            }
            if (delivered) {
                finish(m.id(), "SENT", null);
                sent++;
            } else if (failed && m.attempts() + 1 < properties.maxAttempts()) {
                jdbc.sql("UPDATE push_message SET attempts = attempts + 1, error = 'send failed' WHERE id = :id")
                        .param("id", m.id()).update();
            } else {
                finish(m.id(), failed ? "FAILED" : "NOT_SENT", failed ? "send failed" : "no valid devices");
            }
        }
        return sent;
    }

    private void finish(long id, String status, String error) {
        jdbc.sql("""
                        UPDATE push_message SET status = :status, error = :error, attempts = attempts + 1,
                               sent_at = CASE WHEN :status = 'SENT' THEN CAST(:now AS timestamptz) END
                        WHERE id = :id""")
                .param("status", status).param("error", error).param("id", id)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
    }

    @Scheduled(fixedDelayString = "${latestay.push.send-interval:5s}",
            initialDelayString = "${latestay.push.send-interval:5s}")
    @SchedulerLock(name = SchedulerLockConfig.PUSH_SENDER, lockAtMostFor = "PT2M", lockAtLeastFor = "PT2S")
    public void tick() {
        jobRuns.ran(SchedulerLockConfig.PUSH_SENDER);
        try {
            sendQueued();
        } catch (RuntimeException e) {
            log.error("Push sender failed", e);
        }
    }

    /** 03:50 Asia/Kolkata: tokens not seen for 90 days are deleted. */
    @Scheduled(cron = "${latestay.push.cleanup-cron:0 50 3 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = SchedulerLockConfig.DEVICE_CLEANUP, lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        jobRuns.ran(SchedulerLockConfig.DEVICE_CLEANUP);
        int deleted = devices.deleteNotSeenSince(clock.instant().minus(properties.tokenRetention()));
        if (deleted > 0) {
            log.info("Deleted {} device tokens not seen for {}", deleted, properties.tokenRetention());
        }
    }
}
