package com.latestay.booking;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timeouts and refunds. Each method finds due bookings, then moves each one in its own transaction, which
 * re-checks the deadline under the row lock. Tests call the methods directly with a fixed clock; scheduling is off
 * when {@code latestay.jobs.enabled=false}.
 */
@Component
public class BookingJobs {

    private static final Logger log = LoggerFactory.getLogger(BookingJobs.class);
    static final int REFUND_BATCH = 20;

    private final BookingService bookings;
    private final BookingProperties properties;

    public BookingJobs(BookingService bookings, BookingProperties properties) {
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

    /** HOTEL_CONFIRMED -> NO_SHOW an hour after arrive_by. */
    public int markNoShows() {
        return run("no-show", BookingStatus.HOTEL_CONFIRMED, "arrive_by", properties.noShowAfter(),
                bookings::noShowIfAbsent);
    }

    /** CHECKED_IN -> COMPLETED at leave_at. */
    public int completeStays() {
        return run("complete", BookingStatus.CHECKED_IN, "leave_at", Duration.ZERO, bookings::completeIfLeft);
    }

    public int issueRefunds() {
        return bookings.issueRefunds(REFUND_BATCH);
    }

    @Scheduled(fixedDelayString = "${latestay.jobs.interval:15s}", initialDelayString = "${latestay.jobs.interval:15s}")
    void tick() {
        try {
            expireUnpaid();
            timeoutUnconfirmed();
            markNoShows();
            completeStays();
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
