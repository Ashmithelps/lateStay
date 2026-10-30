package com.latestay.hold;

import com.latestay.booking.BookingProperties;
import com.latestay.hotel.AvailabilityRepository;
import com.latestay.stay.LateStayTime;
import com.latestay.stay.StayWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Room holds: a negotiation that is agreed gets a room for {@code holdWindow} (10 minutes), which its booking then
 * takes over. Available rooms are worked out by the {@code rooms_available} SQL function (owner's latest report,
 * minus active holds, minus live bookings made after the report). Taking a hold locks the night's availability
 * row, so two travellers can't both get the last room.
 */
@Component
public class RoomHolds {

    /** What a session is negotiating for: one room, or two rooms of a group (roomTypeIds in line order). */
    public record Stay(UUID sessionId, UUID hotelId, String hotelName, java.util.List<UUID> roomTypeIds,
            LocalDate nightOf) {

        public int rooms() {
            return roomTypeIds.size();
        }
    }

    public record Hold(UUID id, UUID hotelId, LocalDate nightOf, Instant expiresAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final BookingProperties properties;
    private final AvailabilityRepository availability;

    public RoomHolds(JdbcClient jdbc, Clock clock, BookingProperties properties, AvailabilityRepository availability) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.clock = clock;
        this.properties = properties;
    }

    /** Rooms still sellable for the night (0 if the owner hasn't answered for it). */
    public int available(UUID hotelId, LocalDate nightOf) {
        return availability.available(hotelId, nightOf, clock.instant());
    }

    public Stay stayOf(UUID sessionId) {
        return jdbc.sql("""
                        SELECT s.id, r.hotel_id, h.name, q.arrive_at,
                               ARRAY(SELECT nr.room_type_id FROM negotiation_room nr WHERE nr.session_id = s.id
                                     ORDER BY nr.line_no) AS room_type_ids
                        FROM negotiation_session s
                        JOIN room_type r ON r.id = s.room_type_id
                        JOIN hotel h ON h.id = r.hotel_id
                        JOIN stay_request q ON q.id = s.stay_request_id
                        WHERE s.id = :id""")
                .param("id", sessionId)
                .query((rs, n) -> new Stay(rs.getObject("id", UUID.class), rs.getObject("hotel_id", UUID.class),
                        rs.getString("name"), java.util.List.of((UUID[]) rs.getArray("room_type_ids").getArray()),
                        StayWindow.nightOf(LocalDateTime.ofInstant(
                                rs.getObject("arrive_at", OffsetDateTime.class).toInstant(), LateStayTime.ZONE))))
                .single();
    }

    /**
     * The session's hold, if it still has one that hasn't run out (line 1; a group's two holds are taken, converted
     * and released together).
     */
    public Optional<Hold> activeFor(UUID sessionId) {
        return jdbc.sql("""
                        SELECT id, hotel_id, night_of, expires_at FROM room_hold
                        WHERE session_id = :session AND line_no = 1 AND status = 'ACTIVE' AND expires_at > :now""")
                .param("session", sessionId).param("now", at(clock.instant()))
                .query((rs, n) -> new Hold(rs.getObject("id", UUID.class), rs.getObject("hotel_id", UUID.class),
                        rs.getObject("night_of", LocalDate.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Takes the session's rooms (one, or both of a group's: all or none), or returns its existing hold. Locks the
     * night's availability row first, so a concurrent hold for the same hotel and night waits and then sees this
     * one. Empty if not enough rooms are left. Must run inside a transaction.
     */
    public Optional<Hold> take(UUID sessionId) {
        Optional<Hold> existing = activeFor(sessionId);
        if (existing.isPresent()) {
            return existing;
        }
        Stay stay = stayOf(sessionId);
        boolean reported = jdbc.sql("""
                        SELECT 1 FROM nightly_availability WHERE hotel_id = :hotel AND night_of = :night
                        FOR UPDATE""")
                .param("hotel", stay.hotelId()).param("night", stay.nightOf()).query(Integer.class).optional()
                .isPresent();
        if (!reported || available(stay.hotelId(), stay.nightOf()) < stay.rooms()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        // A hold from an earlier, expired agreement can't stay ACTIVE next to the new one.
        jdbc.sql("UPDATE room_hold SET status = 'EXPIRED', released_at = :now WHERE session_id = :session "
                        + "AND status = 'ACTIVE'")
                .param("session", sessionId).param("now", at(now)).update();
        Hold hold = new Hold(UUID.randomUUID(), stay.hotelId(), stay.nightOf(), now.plus(properties.holdWindow()));
        for (int line = 1; line <= stay.rooms(); line++) {
            jdbc.sql("""
                            INSERT INTO room_hold (id, hotel_id, room_type_id, night_of, session_id, expires_at,
                                   status, created_at, line_no)
                            VALUES (:id, :hotel, :room, :night, :session, :expires, 'ACTIVE', :now, :line)""")
                    .param("id", line == 1 ? hold.id() : UUID.randomUUID()).param("hotel", stay.hotelId())
                    .param("room", stay.roomTypeIds().get(line - 1)).param("night", stay.nightOf())
                    .param("session", sessionId).param("expires", at(hold.expiresAt())).param("now", at(now))
                    .param("line", line).update();
        }
        return Optional.of(hold);
    }

    /**
     * The booking now holds the rooms; the holds stop counting (the live booking counts instead, with its number of
     * rooms). Converts every active hold of the hold's session.
     */
    public void convert(UUID holdId, UUID bookingId) {
        jdbc.sql("""
                        UPDATE room_hold SET status = 'CONVERTED', booking_id = :booking
                        WHERE id = :id OR (status = 'ACTIVE'
                              AND session_id = (SELECT session_id FROM room_hold WHERE id = :id))""")
                .param("id", holdId).param("booking", bookingId).update();
    }

    /** The booking left the live states: record its hold as released (the count already excludes it). */
    public void releaseForBooking(UUID bookingId) {
        jdbc.sql("""
                        UPDATE room_hold SET status = 'RELEASED', released_at = :now
                        WHERE booking_id = :booking AND status IN ('ACTIVE', 'CONVERTED')""")
                .param("booking", bookingId).param("now", at(clock.instant())).update();
    }

    /** Housekeeping: holds past their time become EXPIRED (they already stopped counting). */
    public int expireOld() {
        Instant now = clock.instant();
        return jdbc.sql("""
                        UPDATE room_hold SET status = 'EXPIRED', released_at = :now
                        WHERE status = 'ACTIVE' AND expires_at <= :now""")
                .param("now", at(now)).update();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
