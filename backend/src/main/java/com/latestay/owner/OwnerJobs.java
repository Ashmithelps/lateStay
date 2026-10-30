package com.latestay.owner;

import com.latestay.jobs.JobRuns;
import com.latestay.jobs.SchedulerLockConfig;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes used and expired sign-in links nightly (tests call {@link OwnerSessions#deleteOldLinks} directly). */
@Component
public class OwnerJobs {

    private final OwnerSessions sessions;
    private final JobRuns jobRuns;

    OwnerJobs(OwnerSessions sessions, JobRuns jobRuns) {
        this.jobRuns = jobRuns;
        this.sessions = sessions;
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = SchedulerLockConfig.OWNER_LINK_CLEANUP, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void deleteOldLinks() {
        jobRuns.ran(SchedulerLockConfig.OWNER_LINK_CLEANUP);
        sessions.deleteOldLinks();
    }
}
