package com.latestay.admin;

import com.latestay.admin.AdminViews.AdminBooking;
import com.latestay.admin.AdminViews.AdminNegotiation;
import com.latestay.admin.AdminViews.AdminTurn;
import com.latestay.admin.AdminViews.AvailabilityRow;
import com.latestay.booking.BookingStatus;
import com.latestay.stay.LateStayTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only SQL for the admin panel. */
@Repository
class AdminQueries {

    static final int LIMIT = 200;

    private static final String BOOKING_SELECT = """
            SELECT b.id, b.status, b.hotel_id, h.name AS hotel_name,
                   CASE WHEN b.rooms > 1 THEN b.rooms || ' rooms (' || (SELECT string_agg(rt.name, ' + '
                        ORDER BY br.line_no) FROM booking_room br JOIN room_type rt ON rt.id = br.room_type_id
                        WHERE br.booking_id = b.id) || ')' ELSE r.name END AS room_name, b.guest_name, b.phone,
                   b.guests, b.arrive_at, b.arrive_by, b.leave_at, b.agreed_price, b.deposit_amount, b.pay_at_desk,
                   b.commission_amount, b.code, b.pay_by, b.confirm_by, p.status AS payment_status,
                   p.razorpay_payment_id, p.refund_id, b.created_at, b.updated_at, b.session_id, b.dispute_reason,
                   b.disputed_at, b.arrival_check_sent_at, h.reliability_failures, b.hide_until, b.keep_hidden_at
            FROM booking b
            JOIN hotel h ON h.id = b.hotel_id
            JOIN room_type r ON r.id = b.room_type_id
            LEFT JOIN LATERAL (SELECT * FROM payment p WHERE p.booking_id = b.id ORDER BY p.id DESC LIMIT 1) p
                   ON true""";

    private static final String NEGOTIATION_SELECT = """
            SELECT s.id, s.status, h.name AS hotel_name, r.name AS room_name, s.list_price, s.floor_price,
                   s.current_offer, s.agreed_price, s.round, s.max_rounds,
                   (SELECT count(*) FROM negotiation_turn t WHERE t.session_id = s.id) AS turns,
                   q.arrive_at, q.guests, s.expires_at, s.updated_at,
                   (SELECT b.id FROM booking b WHERE b.session_id = s.id ORDER BY b.created_at DESC LIMIT 1)
                       AS booking_id
            FROM negotiation_session s
            JOIN room_type r ON r.id = s.room_type_id
            JOIN hotel h ON h.id = r.hotel_id
            JOIN stay_request q ON q.id = s.stay_request_id""";

    private final JdbcClient jdbc;
    private final Clock clock;

    AdminQueries(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Live bookings, plus everything touched in the last three days. */
    List<AdminBooking> recentBookings() {
        return jdbc.sql(BOOKING_SELECT + """

                        WHERE b.status IN ('PENDING_PAYMENT', 'DEPOSIT_PAID', 'HOTEL_CONFIRMED', 'CHECKED_IN',
                                           'DISPUTED')
                           OR b.updated_at > :since
                        ORDER BY b.created_at DESC LIMIT :limit""")
                .param("since", at(clock.instant().minus(Duration.ofDays(3)))).param("limit", LIMIT)
                .query(AdminQueries::booking).list();
    }

    Optional<AdminBooking> booking(UUID id) {
        return jdbc.sql(BOOKING_SELECT + " WHERE b.id = :id").param("id", id).query(AdminQueries::booking)
                .optional();
    }

    List<AdminNegotiation> negotiations(boolean live) {
        String where = live
                ? " WHERE s.status IN ('OPEN', 'FINAL_OFFER') AND s.expires_at > :now"
                : " WHERE s.created_at > :since";
        return jdbc.sql(NEGOTIATION_SELECT + where + " ORDER BY s.updated_at DESC LIMIT :limit")
                .param("now", at(clock.instant())).param("since", at(clock.instant().minus(Duration.ofDays(1))))
                .param("limit", LIMIT)
                .query(AdminQueries::negotiation).list();
    }

    Optional<AdminNegotiation> negotiation(UUID id) {
        return jdbc.sql(NEGOTIATION_SELECT + " WHERE s.id = :id").param("id", id).query(AdminQueries::negotiation)
                .optional();
    }

    List<AdminTurn> turns(UUID sessionId) {
        return jdbc.sql("""
                        SELECT seq, role, text, intent::text AS intent, decision::text AS decision, validator_result,
                               latency_ms, created_at
                        FROM negotiation_turn WHERE session_id = :id ORDER BY seq""")
                .param("id", sessionId)
                .query((rs, n) -> new AdminTurn(rs.getInt("seq"), rs.getString("role"), rs.getString("text"),
                        rs.getString("intent"), rs.getString("decision"), rs.getString("validator_result"),
                        (Integer) rs.getObject("latency_ms"), instant(rs, "created_at")))
                .list();
    }

    List<AvailabilityRow> availability(LocalDate nightOf) {
        return jdbc.sql("""
                        SELECT h.id, h.name, h.town, h.active, a.rooms_free, a.reported_at, a.source,
                               rooms_available(h.id, :night, :now) AS available, h.reliability_failures
                        FROM hotel h
                        LEFT JOIN nightly_availability a ON a.hotel_id = h.id AND a.night_of = :night
                        ORDER BY h.active DESC, h.town, h.name""")
                .param("night", nightOf).param("now", at(clock.instant()))
                .query((rs, n) -> new AvailabilityRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("town"), rs.getBoolean("active"), (Integer) rs.getObject("rooms_free"),
                        instant(rs, "reported_at"), rs.getString("source"), rs.getInt("available"),
                        rs.getInt("reliability_failures")))
                .list();
    }

    private static AdminBooking booking(ResultSet rs, int n) throws SQLException {
        return new AdminBooking(rs.getObject("id", UUID.class), BookingStatus.valueOf(rs.getString("status")),
                rs.getObject("hotel_id", UUID.class), rs.getString("hotel_name"), rs.getString("room_name"),
                rs.getString("guest_name"), rs.getString("phone"), rs.getInt("guests"), local(rs, "arrive_at"),
                local(rs, "arrive_by"), local(rs, "leave_at"), rs.getLong("agreed_price"),
                rs.getLong("deposit_amount"), rs.getLong("pay_at_desk"), rs.getLong("commission_amount"),
                rs.getString("code"), instant(rs, "pay_by"), instant(rs, "confirm_by"),
                rs.getString("payment_status"), rs.getString("razorpay_payment_id"), rs.getString("refund_id"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getObject("session_id", UUID.class),
                rs.getString("dispute_reason"), instant(rs, "disputed_at"), instant(rs, "arrival_check_sent_at"),
                rs.getInt("reliability_failures"), instant(rs, "hide_until"), instant(rs, "keep_hidden_at"));
    }

    private static AdminNegotiation negotiation(ResultSet rs, int n) throws SQLException {
        return new AdminNegotiation(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("hotel_name"), rs.getString("room_name"), rs.getLong("list_price"),
                rs.getLong("floor_price"), rs.getLong("current_offer"),
                rs.getObject("agreed_price") == null ? null : rs.getLong("agreed_price"), rs.getInt("round"),
                rs.getInt("max_rounds"), rs.getInt("turns"), local(rs, "arrive_at"), rs.getInt("guests"),
                instant(rs, "expires_at"), instant(rs, "updated_at"), rs.getObject("booking_id", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static LocalDateTime local(ResultSet rs, String column) throws SQLException {
        Instant value = instant(rs, column);
        return value == null ? null : LocalDateTime.ofInstant(value, LateStayTime.ZONE);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
