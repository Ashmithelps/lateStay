package com.latestay.booking;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.latestay.jobs.JobRuns;
import com.latestay.jobs.SchedulerLockConfig;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timeouts, arrival checks, room holds and refunds. Each method finds due bookings, then moves each one in its own transaction, which
 * re-checks the deadline under the row lock. Tests call the methods directly with a fixed clock; scheduling is off
 * when {@code latestay.jobs.enabled=false}.
 */
@Component
public class BookingJobs {

    private static final Logger log = LoggerFactory.getLogger(BookingJobs.class);
    static final int REFUND_BATCH = 20;

    private final BookingService bookings;
    private final BookingProperties properties;
    private final com.latestay.phone.PhoneVerificationService verifications;

    private final JobRuns jobRuns;

    public BookingJobs(BookingService bookings, BookingProperties properties,
            com.latestay.phone.PhoneVerificationService verifications, JobRuns jobRuns) {
        this.jobRuns = jobRuns;
        this.verifications = verifications;
        this.bookings = bookings;
        this.properties = properties;
    }

    /** PENDING_PAYMENT -> EXPIRED once pay_by has passed. */
    public int expireUnpaid() {
        return run("expire", BookingStatus.PENDING_PAYMENT, "pay_by", Duration.ZERO, bookings::expireIfUnpaid);
    }

    /** DEPOSIT_PAID -> HOTEL_TIMEOUT once confirm_by has passed. */
    public int timeoutUnconfirmed() {
        return run("timeout", BookingStatus.DEPOSIT_PAID, "confirm_by", Duration.ZERO, bookings::timeoutIfUnconfirmed);
    }

    /** HOTEL_CONFIRMED, an hour after arrive_by: ask the owner (arrival_check). No status change. */
    public int sendArrivalChecks() {
        int sent = 0;
        for (UUID id : bookings.arrivalChecksDue(properties.arrivalCheckAfter())) {
            try {
                if (bookings.sendArrivalCheckIfDue(id)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Arrival check failed for {}: {}", id, e.getMessage());
            }
        }
        return sent;
    }

    /** HOTEL_CONFIRMED -> COMPLETED when nobody answered the arrival check by complete_by. */
    public int completeUnanswered() {
        return run("auto-complete", BookingStatus.HOTEL_CONFIRMED, "complete_by", Duration.ZERO,
                bookings::completeIfUnanswered);
    }

    public int expireHolds() {
        return bookings.expireHolds();
    }

    /** CHECKED_IN -> COMPLETED at leave_at. */
    public int completeStays() {
        return run("complete", BookingStatus.CHECKED_IN, "leave_at", Duration.ZERO, bookings::completeIfLeft);
    }

    public int issueRefunds() {
        return bookings.issueRefunds(REFUND_BATCH);
    }

    /** Once a night (03:30 India time): phone numbers of stays that ended more than 90 days ago. */
    @Scheduled(cron = "${latestay.jobs.phone-retention-cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = SchedulerLockConfig.PHONE_RETENTION, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void phoneRetention() {
        jobRuns.ran(SchedulerLockConfig.PHONE_RETENTION);
        try {
            int deleted = deleteOldPhoneNumbers();
            if (deleted > 0) {
                log.info("Deleted the phone numbers of {} bookings past retention", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Phone retention job failed", e);
        }
    }

    public int deleteOldPhoneNumbers() {
        int deleted = bookings.deleteOldPhoneNumbers();
        verifications.deleteOld();
        return deleted;
    }

    @Scheduled(fixedDelayString = "${latestay.jobs.interval:15s}", initialDelayString = "${latestay.jobs.interval:15s}")
    @SchedulerLock(name = SchedulerLockConfig.BOOKING_JOBS, lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void tick() {
        jobRuns.ran(SchedulerLockConfig.BOOKING_JOBS);
        try {
            expireUnpaid();
            timeoutUnconfirmed();
            sendArrivalChecks();
            completeUnanswered();
            completeStays();
            expireHolds();
            issueRefunds();
        } catch (RuntimeException e) {
            log.error("Booking jobs failed", e);
        }
    }

    private int run(String name, BookingStatus status, String column, Duration after, Predicate<UUID> move) {
        int moved = 0;
        for (UUID id : bookings.due(status, column, after)) {
            try {
                if (move.test(id)) {
                    moved++;
                }
            } catch (RuntimeException e) {
                log.warn("Booking job {} failed for {}: {}", name, id, e.getMessage());
            }
        }
        return moved;
    }

    @Component
    @EnableScheduling
    @ConditionalOnProperty(name = "latestay.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
