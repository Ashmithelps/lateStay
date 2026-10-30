package com.latestay.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.FreshDatabase;
import com.latestay.LateStayApplication;
import com.latestay.analytics.PostHogForwarder;
import com.latestay.booking.BookingJobs;
import com.latestay.digest.MorningDigest;
import com.latestay.ledger.SettlementJob;
import com.latestay.owner.OwnerJobs;
import com.latestay.whatsapp.AvailabilityPings;
import com.latestay.whatsapp.WhatsAppSender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Two full app instances on one database (what a rolling deploy looks like for a moment). Every scheduled job is
 * fired in both at the same instant; ShedLock lets exactly one of them run it.
 */
class TwoInstancesIT {

    static FreshDatabase db;
    static ConfigurableApplicationContext first;
    static ConfigurableApplicationContext second;

    static ConfigurableApplicationContext start(String name) {
        // Command-line arguments: they win over application.yml (builder properties would not).
        return new SpringApplicationBuilder(LateStayApplication.class).run(
                "--spring.datasource.url=" + db.url,
                "--spring.datasource.username=" + db.username,
                "--spring.datasource.password=" + db.password,
                "--server.port=0",
                // No timers: the test fires each job itself, in both instances at once.
                "--latestay.jobs.enabled=false",
                "--spring.application.name=instance-" + name);
    }

    @BeforeAll
    static void twoInstances() {
        db = FreshDatabase.create("shedlock");
        first = start("a");
        second = start("b");
    }

    @AfterAll
    static void stop() {
        if (first != null) {
            first.close();
        }
        if (second != null) {
            second.close();
        }
    }

    /** Each lock name, and how to fire that job in one instance. */
    static Map<String, Consumer<ConfigurableApplicationContext>> jobs() {
        Map<String, Consumer<ConfigurableApplicationContext>> jobs = new LinkedHashMap<>();
        jobs.put(SchedulerLockConfig.BOOKING_JOBS, c -> c.getBean(BookingJobs.class).tick());
        jobs.put(SchedulerLockConfig.PHONE_RETENTION, c -> c.getBean(BookingJobs.class).phoneRetention());
        jobs.put(SchedulerLockConfig.WHATSAPP_SENDER, c -> c.getBean(WhatsAppSender.class).tick());
        jobs.put(SchedulerLockConfig.NIGHTLY_PING, c -> c.getBean(AvailabilityPings.class).nightly());
        jobs.put(SchedulerLockConfig.WEEKLY_SETTLEMENT, c -> c.getBean(SettlementJob.class).settleWeek());
        jobs.put(SchedulerLockConfig.OWNER_LINK_CLEANUP, c -> c.getBean(OwnerJobs.class).deleteOldLinks());
        jobs.put(SchedulerLockConfig.MORNING_DIGEST, c -> c.getBean(MorningDigest.class).morning());
        jobs.put(SchedulerLockConfig.REVIEW_INVITES, c -> c.getBean(com.latestay.review.ReviewInvites.class).morning());
        jobs.put(SchedulerLockConfig.PUSH_SENDER, c -> c.getBean(com.latestay.push.PushSender.class).tick());
        jobs.put(SchedulerLockConfig.DEVICE_CLEANUP, c -> c.getBean(com.latestay.push.PushSender.class).cleanup());
        jobs.put(SchedulerLockConfig.ANALYTICS_FORWARDER, c -> c.getBean(PostHogForwarder.class).tick());
        return jobs;
    }

    @Test
    void everyScheduledJobRunsOnceAcrossTwoInstances() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (var job : jobs().entrySet()) {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> runs = new ArrayList<>();
                for (ConfigurableApplicationContext instance : List.of(first, second)) {
                    runs.add(pool.submit(() -> {
                        go.await();
                        job.getValue().accept(instance);
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> run : runs) {
                    run.get();
                }
                double total = first.getBean(JobRuns.class).count(job.getKey())
                        + second.getBean(JobRuns.class).count(job.getKey());
                assertThat(total).as(job.getKey()).isEqualTo(1.0);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.jdbc.sql("SELECT count(*) FROM shedlock").query(Integer.class).single())
                .isEqualTo(jobs().size());
    }

    @Test
    void everyScheduledMethodCarriesALock() {
        // Guard against a new @Scheduled job without @SchedulerLock.
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> reader.getAnnotationMetadata()
                .hasAnnotatedMethods(org.springframework.scheduling.annotation.Scheduled.class.getName()));
        List<String> missing = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.latestay")) {
            try {
                for (var method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
                    if (method.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)
                            && !method.isAnnotationPresent(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class)) {
                        missing.add(candidate.getBeanClassName() + "." + method.getName());
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(missing).isEmpty();
    }
}
