package com.latestay.share;

import com.latestay.booking.BookingStatus;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.NotFoundException;
import com.latestay.share.ShareDtos.ShareLink;
import com.latestay.share.ShareDtos.SharedStatus;
import com.latestay.share.ShareDtos.SharedStay;
import com.latestay.stay.LateStayTime;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Share with family": a read-only link to /stay/[token] with the hotel, address, map, arrival time and status.
 * Paid, confirmed and checked-in bookings can share; one live link per booking (a new link turns off the old one).
 * It works until {@link #AFTER_LEAVE} after the leave time, or until the traveller turns it off.
 */
@Service
public class StayShares {

    public static final Duration AFTER_LEAVE = Duration.ofHours(24);
    private static final Set<BookingStatus> CAN_SHARE = Set.of(BookingStatus.DEPOSIT_PAID,
            BookingStatus.HOTEL_CONFIRMED, BookingStatus.CHECKED_IN);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String webUrl;

    public StayShares(JdbcClient jdbc, Clock clock, @Value("${latestay.public-web-url}") String webUrl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    private record BookingRef(BookingStatus status, Instant leaveAt) {
    }

    private BookingRef lockBooking(UUID bookingId) {
        return jdbc.sql("SELECT status, leave_at FROM booking WHERE id = :id FOR UPDATE").param("id", bookingId)
                .query((rs, n) -> new BookingRef(BookingStatus.valueOf(rs.getString("status")),
                        rs.getObject("leave_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> new NotFoundException("booking", bookingId));
    }

    /** A new link (turning off any earlier one). */
    @Transactional
    public ShareLink create(UUID bookingId) {
        BookingRef booking = lockBooking(bookingId);
        if (!CAN_SHARE.contains(booking.status())) {
            throw new ConflictException("You can share your stay once it's paid, until you check in.");
        }
        Instant now = clock.instant();
        Instant expires = booking.leaveAt().plus(AFTER_LEAVE);
        if (!now.isBefore(expires)) {
            throw new ConflictException("This stay is over.");
        }
        revoke(bookingId, now);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        INSERT INTO booking_share (token, booking_id, expires_at, created_at)
                        VALUES (:token, :booking, :expires, :now)""")
                .param("token", token).param("booking", bookingId).param("expires", at(expires))
                .param("now", at(now)).update();
        return link(token, expires);
    }

    /** The booking's live link, if it has one. */
    @Transactional(readOnly = true)
    public Optional<ShareLink> current(UUID bookingId) {
        return jdbc.sql("""
                        SELECT token, expires_at FROM booking_share
                        WHERE booking_id = :booking AND revoked_at IS NULL AND expires_at > :now""")
                .param("booking", bookingId).param("now", at(clock.instant()))
                .query((rs, n) -> link(rs.getString("token"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Turns the link off. */
    @Transactional
    public void turnOff(UUID bookingId) {
        lockBooking(bookingId);
        revoke(bookingId, clock.instant());
    }

    private void revoke(UUID bookingId, Instant now) {
        jdbc.sql("UPDATE booking_share SET revoked_at = :now WHERE booking_id = :booking AND revoked_at IS NULL")
                .param("now", at(now)).param("booking", bookingId).update();
    }

    /**
     * The shared page.
     *
     * @throws NotFoundException for a token that never existed
     * @throws StayShareGoneException once expired or turned off
     */
    @Transactional(readOnly = true)
    public SharedStay view(String token) {
        record Row(Instant expiresAt, Instant revokedAt, SharedStay stay) {
        }
        Row row = jdbc.sql("""
                        SELECT s.expires_at, s.revoked_at, b.status, b.arrive_at, h.name, h.address, h.town,
                               ST_Y(h.location::geometry) AS lat, ST_X(h.location::geometry) AS lng
                        FROM booking_share s
                        JOIN booking b ON b.id = s.booking_id
                        JOIN hotel h ON h.id = b.hotel_id
                        WHERE s.token = :token""")
                .param("token", token)
                .query((rs, n) -> {
                    Instant expires = rs.getObject("expires_at", OffsetDateTime.class).toInstant();
                    OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
                    return new Row(expires, revoked == null ? null : revoked.toInstant(),
                            new SharedStay(rs.getString("name"), rs.getString("address"), rs.getString("town"),
                                    rs.getDouble("lat"), rs.getDouble("lng"),
                                    LocalDateTime.ofInstant(rs.getObject("arrive_at", OffsetDateTime.class)
                                            .toInstant(), LateStayTime.ZONE),
                                    status(BookingStatus.valueOf(rs.getString("status"))), expires));
                })
                .optional().orElseThrow(() -> new NotFoundException("shared stay", token));
        if (row.revokedAt() != null) {
            throw new StayShareGoneException(StayShareGoneException.Reason.TURNED_OFF);
        }
        if (!clock.instant().isBefore(row.expiresAt())) {
            throw new StayShareGoneException(StayShareGoneException.Reason.EXPIRED);
        }
        return row.stay();
    }

    static SharedStatus status(BookingStatus status) {
        return switch (status) {
            case DEPOSIT_PAID -> SharedStatus.WAITING;
            case HOTEL_CONFIRMED -> SharedStatus.CONFIRMED;
            case CHECKED_IN -> SharedStatus.CHECKED_IN;
            case COMPLETED -> SharedStatus.FINISHED;
            default -> SharedStatus.ENDED;
        };
    }

    private ShareLink link(String token, Instant expires) {
        return new ShareLink(token, webUrl + "/stay/" + token, expires);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
