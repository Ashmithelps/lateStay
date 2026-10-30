package com.latestay.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counts scheduled-job runs that got their lock ({@code latestay.job.runs{job=...}}): what monitoring reads, and
 * how the two-instance test proves each job ran once.
 */
@Component
public class JobRuns {

    private final MeterRegistry registry;

    public JobRuns(MeterRegistry registry) {
        this.registry = registry;
    }

    public void ran(String job) {
        registry.counter("latestay.job.runs", "job", job).increment();
    }

    public double count(String job) {
        var counter = registry.find("latestay.job.runs").tag("job", job).counter();
        return counter == null ? 0 : counter.count();
    }
}
