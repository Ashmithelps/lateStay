package com.latestay.reliability;

import com.latestay.booking.BookingStatus;
import com.latestay.booking.BookingStatusChanged;
import com.latestay.hotel.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hotel reliability over the last 30 days, worked out from booking_event: confirm rate, median minutes to confirm,
 * rescues and upheld disputes. Search ranks by it; a hotel below the threshold is paused (hidden from search, no
 * new negotiations) until an admin presses "Reviewed, show again", after which only newer incidents count towards
 * pausing it again.
 */
@Service
public class Reliability {

    private static final Logger log = LoggerFactory.getLogger(Reliability.class);
    /** Changes that can push a hotel below the threshold. */
    private static final Set<BookingStatus> INCIDENTS = Set.of(BookingStatus.HOTEL_DECLINED,
            BookingStatus.HOTEL_TIMEOUT, BookingStatus.HOTEL_FAILED, BookingStatus.COMPLETED);

    private final JdbcClient jdbc;
    private final ReliabilityProperties properties;
    private final Clock clock;

    public Reliability(JdbcClient jdbc, ReliabilityProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public ReliabilityProperties properties() {
        return properties;
    }

    public HotelReliability of(UUID hotelId) {
        return forHotels(List.of(hotelId)).get(hotelId);
    }

    /** Every hotel asked for is in the map (a hotel with no bookings is new, score 80). */
    public Map<UUID, HotelReliability> forHotels(Collection<UUID> hotelIds) {
        return compute(hotelIds, false);
    }

    /**
     * @param sinceReview count only events after the hotel's last admin review too (for pausing); the numbers shown
     *     always cover the whole window
     */
    private Map<UUID, HotelReliability> compute(Collection<UUID> hotelIds, boolean sinceReview) {
        Map<UUID, HotelReliability> result = new HashMap<>();
        if (hotelIds.isEmpty()) {
            return result;
        }
        Instant since = clock.instant().minus(properties.window());
        jdbc.sql("""
                        WITH ev AS (
                            SELECT b.hotel_id, e.from_status, e.to_status, b.dispute_reason, b.paid_at,
                                   b.confirmed_at
                            FROM booking_event e
                            JOIN booking b ON b.id = e.booking_id
                            JOIN hotel h ON h.id = b.hotel_id
                            WHERE b.hotel_id IN (:ids) AND e.created_at > :since AND e.created_at <= :now
                              AND (NOT :sinceReview OR h.reliability_reviewed_at IS NULL
                                   OR e.created_at > h.reliability_reviewed_at)
                        )
                        SELECT h.id AS hotel_id, h.reliability_paused_at,
                               count(ev.*) FILTER (WHERE ev.to_status = 'HOTEL_CONFIRMED') AS confirmed,
                               count(ev.*) FILTER (WHERE ev.to_status = 'HOTEL_DECLINED') AS declined,
                               count(ev.*) FILTER (WHERE ev.to_status = 'HOTEL_TIMEOUT') AS timed_out,
                               percentile_cont(0.5) WITHIN GROUP (
                                   ORDER BY extract(epoch FROM ev.confirmed_at - ev.paid_at) / 60.0)
                                   FILTER (WHERE ev.to_status = 'HOTEL_CONFIRMED') AS median_minutes,
                               count(ev.*) FILTER (WHERE ev.to_status = 'HOTEL_FAILED'
                                                     AND ev.from_status IS DISTINCT FROM 'DISPUTED') AS rescues,
                               count(ev.*) FILTER (WHERE ev.from_status = 'DISPUTED'
                                   AND (ev.to_status = 'HOTEL_FAILED'
                                        OR (ev.to_status = 'COMPLETED'
                                            AND ev.dispute_reason = 'TRAVELLER_ARRIVED'))) AS upheld
                        FROM hotel h LEFT JOIN ev ON ev.hotel_id = h.id
                        WHERE h.id IN (:ids)
                        GROUP BY h.id, h.reliability_paused_at""")
                .param("ids", hotelIds).param("since", at(since)).param("now", at(clock.instant()))
                .param("sinceReview", sinceReview)
                .query(rs -> {
                    double median = rs.getDouble("median_minutes");
                    Double medianOrNull = rs.wasNull() ? null : Math.round(median * 10) / 10.0;
                    OffsetDateTime paused = rs.getObject("reliability_paused_at", OffsetDateTime.class);
                    result.put(rs.getObject("hotel_id", UUID.class), HotelReliability.of(rs.getInt("confirmed"),
                            rs.getInt("declined"), rs.getInt("timed_out"), medianOrNull, rs.getInt("rescues"),
                            rs.getInt("upheld"), paused == null ? null : paused.toInstant(), properties));
                });
        return result;
    }

    /** Re-checks the hotel after a decline, timeout, rescue or settled dispute, in the booking's transaction. */
    @EventListener
    void on(BookingStatusChanged change) {
        if (INCIDENTS.contains(change.to())
                && (change.to() != BookingStatus.COMPLETED || change.from() == BookingStatus.DISPUTED)) {
            check(change.booking().hotelId());
        }
    }

    /** Pauses the hotel if it is below the threshold (counting only incidents since its last review). */
    public boolean check(UUID hotelId) {
        HotelReliability r = compute(List.of(hotelId), true).get(hotelId);
        if (r == null || r.pausedAt() != null || !r.belowThreshold(properties)) {
            return false;
        }
        jdbc.sql("UPDATE hotel SET reliability_paused_at = :now WHERE id = :id AND reliability_paused_at IS NULL")
                .param("now", at(clock.instant())).param("id", hotelId).update();
        log.warn("Hotel {} paused from search: confirm rate {}%, {} rescues, {} upheld disputes", hotelId,
                r.confirmRatePercent(), r.rescues(), r.upheldDisputes());
        return true;
    }

    /** The admin's "Reviewed, show again". */
    @Transactional
    public void reviewed(UUID hotelId) {
        int updated = jdbc.sql("""
                        UPDATE hotel SET reliability_paused_at = NULL, reliability_reviewed_at = :now
                        WHERE id = :id""")
                .param("now", at(clock.instant())).param("id", hotelId).update();
        if (updated == 0) {
            throw new NotFoundException("hotel", hotelId);
        }
    }

    public boolean paused(UUID hotelId) {
        return jdbc.sql("SELECT reliability_paused_at IS NOT NULL FROM hotel WHERE id = :id").param("id", hotelId)
                .query(Boolean.class).optional().orElse(false);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
