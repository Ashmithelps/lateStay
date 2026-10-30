package com.latestay.whatsapp;

import com.latestay.jobs.JobRuns;
import com.latestay.jobs.SchedulerLockConfig;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.stay.LateStayTime;
import com.latestay.stay.StayWindow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks every active hotel at 21:00 how many rooms are free tonight (template nightly_availability). A hotel that
 * hasn't answered has no availability for the night and stays out of search.
 */
@Component
public class AvailabilityPings {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityPings.class);

    private final HotelRepository hotels;
    private final WhatsAppOutbox outbox;
    private final WhatsAppProperties properties;
    private final Clock clock;
    private final JobRuns jobRuns;

    public AvailabilityPings(HotelRepository hotels, WhatsAppOutbox outbox, WhatsAppProperties properties,
            Clock clock, JobRuns jobRuns) {
        this.jobRuns = jobRuns;
        this.hotels = hotels;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${latestay.whatsapp.ping-cron:0 0 21 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = SchedulerLockConfig.NIGHTLY_PING, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void nightly() {
        jobRuns.ran(SchedulerLockConfig.NIGHTLY_PING);
        try {
            log.info("Nightly availability ping queued for {} hotels", pingAll());
        } catch (RuntimeException e) {
            log.error("Nightly availability ping failed", e);
        }
    }

    /** Queues tonight's ping for every active hotel, once per hotel and night. Returns how many were queued. */
    @Transactional
    public int pingAll() {
        LocalDate night = tonight();
        int queued = 0;
        for (Hotel hotel : hotels.findAll()) {
            if (hotel.active() && queue(hotel, night, "nightly_availability:" + hotel.id() + ":" + night)) {
                queued++;
            }
        }
        return queued;
    }

    /** The admin's "Send availability ping now": always sends, even if tonight's ping already went out. */
    @Transactional
    public void pingNow(UUID hotelId) {
        Hotel hotel = hotels.find(hotelId).orElseThrow(() -> new NotFoundException("hotel", hotelId));
        LocalDate night = tonight();
        queue(hotel, night, "nightly_availability:" + hotel.id() + ":" + night + ":manual:" + clock.millis());
    }

    LocalDate tonight() {
        return StayWindow.nightOf(LocalDateTime.ofInstant(clock.instant(), LateStayTime.ZONE));
    }

    private boolean queue(Hotel hotel, LocalDate night, String dedupeKey) {
        return outbox.queue(hotel.id(), WhatsAppMessages.nightlyAvailability(WhatsAppOutbox.waId(hotel.whatsappNumber()),
                properties.language(), hotel.id(), hotel.name(), night), null, dedupeKey);
    }
}
