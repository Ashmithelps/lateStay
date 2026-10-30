package com.latestay.digest;

import com.latestay.jobs.JobRuns;
import com.latestay.jobs.SchedulerLockConfig;
import com.latestay.whatsapp.WhatsAppMessages;
import com.latestay.whatsapp.WhatsAppOutbox;
import com.latestay.whatsapp.WhatsAppProperties;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 09:00 Asia/Kolkata: last night's numbers to ADMIN_WHATSAPP_NUMBER (template morning_digest), once per night. */
@Component
public class MorningDigest {

    private static final Logger log = LoggerFactory.getLogger(MorningDigest.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    static final int HOTELS_LISTED = 3;

    private final DigestService digests;
    private final WhatsAppOutbox outbox;
    private final WhatsAppProperties properties;
    private final JobRuns jobRuns;

    MorningDigest(DigestService digests, WhatsAppOutbox outbox, WhatsAppProperties properties, JobRuns jobRuns) {
        this.digests = digests;
        this.outbox = outbox;
        this.properties = properties;
        this.jobRuns = jobRuns;
    }

    @Scheduled(cron = "${latestay.jobs.digest-cron:0 0 9 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = SchedulerLockConfig.MORNING_DIGEST, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void morning() {
        jobRuns.ran(SchedulerLockConfig.MORNING_DIGEST);
        try {
            send();
        } catch (RuntimeException e) {
            log.error("Morning digest failed", e);
        }
    }

    /** Queues last night's digest (once per night). @return false if no admin number or already queued */
    @Transactional
    public boolean send() {
        if (properties.adminNumber().isBlank()) {
            log.warn("ADMIN_WHATSAPP_NUMBER not set: morning digest not sent");
            return false;
        }
        Digest d = digests.forNight(digests.lastNight());
        return outbox.queue(null, WhatsAppMessages.morningDigest(WhatsAppOutbox.waId(properties.adminNumber()),
                properties.language(), values(d)), null, WhatsAppMessages.MORNING_DIGEST + ":" + d.night(),
                WhatsAppOutbox.Audience.ADMIN);
    }

    static List<String> values(Digest d) {
        return List.of(DAY.format(d.night()), n(d.bookings()), n(d.checkedIn()), n(d.completed()), n(d.noShows()),
                n(d.rescues()), n(d.openDisputes()), n(d.refunds()), rupees(d.refundsPaise()),
                rupees(d.commissionPaise()), rupees(d.owedToHotelsPaise()), hotels(d.noAnswer()),
                rupees(d.llmCostPaise()));
    }

    /** "Pine Crest, Cedar Loft, Deodar Nest +2 more", or "none". */
    static String hotels(List<String> names) {
        if (names.isEmpty()) {
            return "none";
        }
        String shown = String.join(", ", names.subList(0, Math.min(HOTELS_LISTED, names.size())));
        return names.size() > HOTELS_LISTED ? shown + " +" + (names.size() - HOTELS_LISTED) + " more" : shown;
    }

    /** 12345 paise -> "123.45"; whole rupees without decimals: "1,100" (the ₹ is in the template). */
    static String rupees(long paise) {
        var format = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(paise % 100 == 0 ? 0 : 2);
        format.setMaximumFractionDigits(2);
        return format.format(java.math.BigDecimal.valueOf(paise, 2));
    }

    private static String n(int count) {
        return String.valueOf(count);
    }
}
