package com.latestay.digest;

import com.latestay.stay.LateStayTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Works out {@link Digest}: one night's bookings and the 24 hours to 09:00 India time after it. */
@Service
public class DigestService {

    static final LocalTime MORNING = LocalTime.of(9, 0);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final BigDecimal usdInr;

    DigestService(JdbcClient jdbc, Clock clock, @Value("${latestay.llm.usd-inr-rate:88}") BigDecimal usdInr) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.usdInr = usdInr;
    }

    /** The night that ended this morning (or, before 09:00, the one before: its digest is the latest complete). */
    public LocalDate lastNight() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), LateStayTime.ZONE);
        LocalDate today = now.toLocalDate();
        return now.toLocalTime().isBefore(MORNING) ? today.minusDays(2) : today.minusDays(1);
    }

    public Digest forNight(LocalDate night) {
        Instant from = night.atTime(MORNING).atZone(LateStayTime.ZONE).toInstant();
        Instant to = night.plusDays(1).atTime(MORNING).atZone(LateStayTime.ZONE).toInstant();
        record Counts(int bookings, int checkedIn, int completed, int noShows, int rescues) {
        }
        Counts c = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE paid_at IS NOT NULL) AS bookings,
                               count(*) FILTER (WHERE checked_in_at IS NOT NULL) AS checked_in,
                               count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                               count(*) FILTER (WHERE status = 'NO_SHOW') AS no_shows,
                               count(*) FILTER (WHERE status = 'HOTEL_FAILED') AS rescues
                        FROM booking WHERE night_of = :night""")
                .param("night", night)
                .query((rs, n) -> new Counts(rs.getInt("bookings"), rs.getInt("checked_in"), rs.getInt("completed"),
                        rs.getInt("no_shows"), rs.getInt("rescues"))).single();
        int disputes = jdbc.sql("SELECT count(*) FROM booking WHERE status = 'DISPUTED'").query(Integer.class).single();
        record Refunds(int count, long paise) {
        }
        Refunds r = jdbc.sql("""
                        SELECT count(*) AS n, COALESCE(sum(amount), 0) * 100 AS paise FROM payment
                        WHERE status = 'REFUNDED' AND updated_at >= :from AND updated_at < :to""")
                .param("from", at(from)).param("to", at(to))
                .query((rs, n) -> new Refunds(rs.getInt("n"), rs.getLong("paise"))).single();
        // Commission on bookings the hotel is still owed for (none once refunded), minus what promo codes cost.
        long commission = jdbc.sql("""
                        SELECT COALESCE(sum(CASE e.type WHEN 'COMMISSION' THEN -e.amount_paise
                                                        WHEN 'PROMO_SUBSIDY' THEN -e.amount_paise END), 0)
                        FROM ledger_entry e JOIN booking b ON b.id = e.booking_id
                        WHERE b.night_of = :night AND e.type IN ('COMMISSION', 'PROMO_SUBSIDY')
                          AND NOT EXISTS (SELECT 1 FROM ledger_entry x WHERE x.booking_id = b.id AND x.type = 'REFUND')""")
                .param("night", night).query(Long.class).single();
        long owed = jdbc.sql("SELECT COALESCE(sum(amount_paise), 0) FROM ledger_entry").query(Long.class).single();
        List<String> noAnswer = jdbc.sql("""
                        SELECT h.name FROM hotel h
                        WHERE h.active AND NOT EXISTS (SELECT 1 FROM nightly_availability a
                                                       WHERE a.hotel_id = h.id AND a.night_of = :night)
                        ORDER BY h.name""")
                .param("night", night).query(String.class).list();
        long llmMicroUsd = jdbc.sql("""
                        SELECT COALESCE(sum(llm_cost_micro_usd), 0) FROM negotiation_turn
                        WHERE created_at >= :from AND created_at < :to""")
                .param("from", at(from)).param("to", at(to)).query(Long.class).single();
        return new Digest(night, from, to, c.bookings(), c.checkedIn(), c.completed(), c.noShows(), c.rescues(),
                disputes, r.count(), r.paise(), commission, owed, noAnswer, paise(llmMicroUsd), llmMicroUsd);
    }

    /** Micro-dollars to paise at USD_INR_RATE. */
    long paise(long microUsd) {
        return BigDecimal.valueOf(microUsd).multiply(usdInr).movePointLeft(4).setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static OffsetDateTime at(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
