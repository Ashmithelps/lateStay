package com.latestay.booking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {

    static final String COLUMNS = """
            b.id, b.session_id, b.hotel_id, b.room_type_id, b.night_of, b.guest_name, b.phone, b.guests,
            b.arrive_at, b.arrive_by, b.leave_at, b.agreed_price, b.deposit_amount, b.pay_at_desk,
            b.commission_amount, b.code, b.status, b.pay_by, b.confirm_by, b.paid_at, b.confirmed_at,
            b.checked_in_at, b.closed_at, b.created_at, b.updated_at""";

    /** The negotiation a booking is made from. */
    public record AgreedSession(UUID id, String status, Long agreedPrice, UUID roomTypeId, UUID hotelId,
            UUID stayRequestId) {
    }

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the session row so two bookings for one negotiation can't race. */
    public Optional<AgreedSession> lockSession(UUID sessionId) {
        return session(sessionId, " FOR UPDATE OF s");
    }

    public Optional<AgreedSession> findSession(UUID sessionId) {
        return session(sessionId, "");
    }

    private Optional<AgreedSession> session(UUID sessionId, String lock) {
        return jdbc.sql("""
                        SELECT s.id, s.status, s.agreed_price, s.room_type_id, r.hotel_id, s.stay_request_id
                        FROM negotiation_session s JOIN room_type r ON r.id = s.room_type_id
                        WHERE s.id = :id""" + lock)
                .param("id", sessionId)
                .query((rs, n) -> new AgreedSession(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getObject("agreed_price") == null ? null : rs.getLong("agreed_price"),
                        rs.getObject("room_type_id", UUID.class), rs.getObject("hotel_id", UUID.class),
                        rs.getObject("stay_request_id", UUID.class)))
                .optional();
    }

    public void insert(Booking b) {
        jdbc.sql("""
                        INSERT INTO booking (id, session_id, hotel_id, room_type_id, night_of, guest_name, phone,
                               guests, arrive_at, arrive_by, leave_at, agreed_price, deposit_amount, pay_at_desk,
                               commission_amount, code, status, pay_by, created_at, updated_at)
                        VALUES (:id, :session, :hotel, :room, :night, :name, :phone, :guests, :arriveAt, :arriveBy,
                                :leaveAt, :agreed, :deposit, :desk, :commission, :code, :status, :payBy, :created,
                                :updated)""")
                .param("id", b.id()).param("session", b.sessionId()).param("hotel", b.hotelId())
                .param("room", b.roomTypeId()).param("night", b.nightOf()).param("name", b.guestName())
                .param("phone", b.phone()).param("guests", b.guests()).param("arriveAt", at(b.arriveAt()))
                .param("arriveBy", at(b.arriveBy())).param("leaveAt", at(b.leaveAt()))
                .param("agreed", b.agreedPrice()).param("deposit", b.depositAmount()).param("desk", b.payAtDesk())
                .param("commission", b.commissionAmount()).param("code", b.code())
                .param("status", b.status().name()).param("payBy", at(b.payBy()))
                .param("created", at(b.createdAt())).param("updated", at(b.updatedAt()))
                .update();
    }

    public Optional<Booking> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking b WHERE b.id = :id")
                .param("id", id).query(BookingRepository::map).optional();
    }

    public Optional<Booking> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking b WHERE b.id = :id FOR UPDATE")
                .param("id", id).query(BookingRepository::map).optional();
    }

    public Optional<Booking> liveForSession(UUID sessionId) {
        return jdbc.sql("""
                        SELECT %s FROM booking b
                        WHERE b.session_id = :session AND b.status NOT IN ('EXPIRED', 'CANCELLED')""".formatted(COLUMNS))
                .param("session", sessionId).query(BookingRepository::map).optional();
    }

    /** Writes status, guest details and timestamps. */
    public void update(Booking b) {
        jdbc.sql("""
                        UPDATE booking SET status = :status, guest_name = :name, phone = :phone,
                               confirm_by = :confirmBy, paid_at = :paidAt, confirmed_at = :confirmedAt,
                               checked_in_at = :checkedInAt, closed_at = :closedAt, updated_at = :updated
                        WHERE id = :id""")
                .param("id", b.id()).param("status", b.status().name()).param("name", b.guestName())
                .param("phone", b.phone()).param("confirmBy", at(b.confirmBy())).param("paidAt", at(b.paidAt()))
                .param("confirmedAt", at(b.confirmedAt())).param("checkedInAt", at(b.checkedInAt()))
                .param("closedAt", at(b.closedAt())).param("updated", at(b.updatedAt()))
                .update();
    }

    public void event(UUID bookingId, BookingStatus from, BookingStatus to, Actor actor, Instant now) {
        jdbc.sql("""
                        INSERT INTO booking_event (booking_id, from_status, to_status, actor, created_at)
                        VALUES (:booking, :from, :to, :actor, :now)""")
                .param("booking", bookingId).param("from", from == null ? null : from.name())
                .param("to", to.name()).param("actor", actor.name()).param("now", at(now))
                .update();
    }

    public List<BookingEvent> events(UUID bookingId) {
        return jdbc.sql("""
                        SELECT from_status, to_status, actor, created_at FROM booking_event
                        WHERE booking_id = :booking ORDER BY id""")
                .param("booking", bookingId)
                .query((rs, n) -> new BookingEvent(
                        rs.getString("from_status") == null ? null : BookingStatus.valueOf(rs.getString("from_status")),
                        BookingStatus.valueOf(rs.getString("to_status")), Actor.valueOf(rs.getString("actor")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Desk codes already used by the hotel's live bookings that night. */
    public Set<String> codesInUse(UUID hotelId, LocalDate nightOf) {
        return Set.copyOf(jdbc.sql("""
                        SELECT code FROM booking
                        WHERE hotel_id = :hotel AND night_of = :night
                          AND status IN ('PENDING_PAYMENT', 'DEPOSIT_PAID', 'HOTEL_CONFIRMED', 'CHECKED_IN')""")
                .param("hotel", hotelId).param("night", nightOf).query(String.class).list());
    }

    /** Takes one room off the night's availability; false if none is left. */
    public boolean takeRoom(UUID hotelId, LocalDate nightOf) {
        return jdbc.sql("""
                        UPDATE nightly_availability SET rooms_free = rooms_free - 1
                        WHERE hotel_id = :hotel AND night_of = :night AND rooms_free > 0""")
                .param("hotel", hotelId).param("night", nightOf).update() == 1;
    }

    public void releaseRoom(UUID hotelId, LocalDate nightOf) {
        jdbc.sql("""
                        UPDATE nightly_availability SET rooms_free = rooms_free + 1
                        WHERE hotel_id = :hotel AND night_of = :night""")
                .param("hotel", hotelId).param("night", nightOf).update();
    }

    /** Ids of bookings in {@code status} whose {@code column} deadline is at or before {@code cutoff}. */
    public List<UUID> due(BookingStatus status, String column, Instant cutoff) {
        if (!Set.of("pay_by", "confirm_by", "arrive_by", "leave_at").contains(column)) {
            throw new IllegalArgumentException(column);
        }
        return jdbc.sql("SELECT id FROM booking WHERE status = :status AND " + column + " <= :cutoff ORDER BY "
                        + column)
                .param("status", status.name()).param("cutoff", at(cutoff)).query(UUID.class).list();
    }

    /**
     * Deletes phone numbers of stays that ended before {@code cutoff}, including the copy Razorpay sent in the
     * payment webhook (its "contact" field), by dropping the stored raw event. Returns how many bookings changed.
     */
    public int deletePhonesBefore(Instant cutoff, Instant now) {
        jdbc.sql("""
                        UPDATE payment SET raw_event = NULL, updated_at = :now
                        WHERE raw_event IS NOT NULL AND booking_id IN (
                            SELECT id FROM booking WHERE phone IS NOT NULL AND leave_at <= :cutoff)""")
                .param("cutoff", at(cutoff)).param("now", at(now)).update();
        return jdbc.sql("""
                        UPDATE booking SET phone = NULL, phone_deleted_at = :now, updated_at = :now
                        WHERE phone IS NOT NULL AND leave_at <= :cutoff""")
                .param("cutoff", at(cutoff)).param("now", at(now)).update();
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static Booking map(ResultSet rs, int n) throws SQLException {
        return new Booking(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getObject("hotel_id", UUID.class), rs.getObject("room_type_id", UUID.class),
                rs.getObject("night_of", LocalDate.class), rs.getString("guest_name"), rs.getString("phone"),
                rs.getInt("guests"), instant(rs, "arrive_at"), instant(rs, "arrive_by"), instant(rs, "leave_at"),
                rs.getLong("agreed_price"), rs.getLong("deposit_amount"), rs.getLong("pay_at_desk"),
                rs.getLong("commission_amount"), rs.getString("code"), BookingStatus.valueOf(rs.getString("status")),
                instant(rs, "pay_by"), instant(rs, "confirm_by"), instant(rs, "paid_at"), instant(rs, "confirmed_at"),
                instant(rs, "checked_in_at"), instant(rs, "closed_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
