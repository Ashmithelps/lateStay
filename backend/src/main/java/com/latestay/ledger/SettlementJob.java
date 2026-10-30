package com.latestay.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.latestay.jobs.JobRuns;
import com.latestay.jobs.SchedulerLockConfig;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Monday 10:00 Asia/Kolkata: this week's settlements (tests call {@link Settlements#settleWeek} directly). */
@Component
public class SettlementJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);

    private final Settlements settlements;
    private final JobRuns jobRuns;

    SettlementJob(Settlements settlements, JobRuns jobRuns) {
        this.jobRuns = jobRuns;
        this.settlements = settlements;
    }

    @Scheduled(cron = "${latestay.jobs.settlement-cron:0 0 10 * * MON}", zone = "Asia/Kolkata")
    @SchedulerLock(name = SchedulerLockConfig.WEEKLY_SETTLEMENT, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void settleWeek() {
        jobRuns.ran(SchedulerLockConfig.WEEKLY_SETTLEMENT);
        int made = settlements.settleWeek();
        log.info("Weekly settlements: {} made", made);
    }
}
