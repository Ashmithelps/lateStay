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
            b.checked_in_at, b.closed_at, b.created_at, b.updated_at, b.arrival_check_sent_at, b.complete_by,
            b.dispute_reason, b.disputed_at, b.settled_at, b.hold_id""";

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
        insert(b, 0);
    }

    /** @param promoDiscountPaise taken off the deposit by a promo code (deposit_amount is what's paid online) */
    public void insert(Booking b, long promoDiscountPaise) {
        jdbc.sql("""
                        INSERT INTO booking (id, session_id, hotel_id, room_type_id, night_of, guest_name, phone,
                               guests, arrive_at, arrive_by, leave_at, agreed_price, deposit_amount, pay_at_desk,
                               commission_amount, code, status, pay_by, created_at, updated_at, hold_id,
                               promo_discount_paise, rooms)
                        VALUES (:id, :session, :hotel, :room, :night, :name, :phone, :guests, :arriveAt, :arriveBy,
                                :leaveAt, :agreed, :deposit, :desk, :commission, :code, :status, :payBy, :created,
                                :updated, :hold, :promo,
                                COALESCE((SELECT rooms FROM negotiation_session WHERE id = :session), 1))""")
                .param("promo", promoDiscountPaise)
                .param("id", b.id()).param("session", b.sessionId()).param("hotel", b.hotelId())
                .param("room", b.roomTypeId()).param("night", b.nightOf()).param("name", b.guestName())
                .param("phone", b.phone()).param("guests", b.guests()).param("arriveAt", at(b.arriveAt()))
                .param("arriveBy", at(b.arriveBy())).param("leaveAt", at(b.leaveAt()))
                .param("agreed", b.agreedPrice()).param("deposit", b.depositAmount()).param("desk", b.payAtDesk())
                .param("commission", b.commissionAmount()).param("code", b.code())
                .param("status", b.status().name()).param("payBy", at(b.payBy()))
                .param("created", at(b.createdAt())).param("updated", at(b.updatedAt())).param("hold", b.holdId())
                .update();
        // The booking's rooms are the negotiation's (line 1 is booking.room_type_id).
        int lines = jdbc.sql("""
                        INSERT INTO booking_room (booking_id, line_no, room_type_id)
                        SELECT :id, line_no, room_type_id FROM negotiation_room WHERE session_id = :session""")
                .param("id", b.id()).param("session", b.sessionId()).update();
        if (lines == 0) {
            jdbc.sql("INSERT INTO booking_room (booking_id, line_no, room_type_id) VALUES (:id, 1, :room)")
                    .param("id", b.id()).param("room", b.roomTypeId()).update();
        }
    }

    /** How many rooms the booking takes, and their names in order ("Deluxe + Standard"). */
    public record Rooms(int count, String names) {

        /** "Deluxe", or "2 rooms (Deluxe + Standard)". */
        public String describe() {
            return count == 1 ? names : count + " rooms (" + names + ")";
        }
    }

    public Rooms rooms(UUID bookingId) {
        return jdbc.sql("""
                        SELECT b.rooms, (SELECT string_agg(rt.name, ' + ' ORDER BY br.line_no) FROM booking_room br
                                         JOIN room_type rt ON rt.id = br.room_type_id
                                         WHERE br.booking_id = b.id) AS names
                        FROM booking b WHERE b.id = :id""")
                .param("id", bookingId)
                .query((rs, n) -> new Rooms(rs.getInt("rooms"), rs.getString("names") == null ? "Room"
                        : rs.getString("names")))
                .single();
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
                               checked_in_at = :checkedInAt, closed_at = :closedAt, updated_at = :updated,
                               arrival_check_sent_at = :checkSent, complete_by = :completeBy,
                               dispute_reason = :disputeReason, disputed_at = :disputedAt, settled_at = :settledAt
                        WHERE id = :id""")
                .param("id", b.id()).param("status", b.status().name()).param("name", b.guestName())
                .param("phone", b.phone()).param("confirmBy", at(b.confirmBy())).param("paidAt", at(b.paidAt()))
                .param("confirmedAt", at(b.confirmedAt())).param("checkedInAt", at(b.checkedInAt()))
                .param("closedAt", at(b.closedAt())).param("updated", at(b.updatedAt()))
                .param("checkSent", at(b.arrivalCheckSentAt())).param("completeBy", at(b.completeBy()))
                .param("disputeReason", b.disputeReason()).param("disputedAt", at(b.disputedAt()))
                .param("settledAt", at(b.settledAt()))
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

    /** Confirmed bookings whose arrival check is due (arrive_by at or before {@code cutoff}) and not yet sent. */
    public List<UUID> arrivalChecksDue(Instant cutoff) {
        return jdbc.sql("""
                        SELECT id FROM booking
                        WHERE status = 'HOTEL_CONFIRMED' AND arrival_check_sent_at IS NULL AND arrive_by <= :cutoff
                        ORDER BY arrive_by""")
                .param("cutoff", at(cutoff)).query(UUID.class).list();
    }

    /** Was this booking ever disputed? (A traveller gets one dispute.) */
    public boolean everDisputed(UUID bookingId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM booking_event WHERE booking_id = :id AND to_status = 'DISPUTED')")
                .param("id", bookingId).query(Boolean.class).single();
    }

    /** Live or no-show bookings of these hotels whose desk code is {@code code} (the owner typing the code). */
    public List<Booking> withCode(List<UUID> hotelIds, String code) {
        if (hotelIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking b WHERE b.hotel_id IN (:hotels) AND b.code = :code "
                        + "AND b.status IN ('HOTEL_CONFIRMED', 'NO_SHOW') ORDER BY b.arrive_at DESC")
                .param("hotels", hotelIds).param("code", code).query(BookingRepository::map).list();
    }

    public UUID stayRequestOf(UUID sessionId) {
        return jdbc.sql("SELECT stay_request_id FROM negotiation_session WHERE id = :id").param("id", sessionId)
                .query(UUID.class).single();
    }

    public void rescueSearch(UUID bookingId, UUID stayRequestId) {
        jdbc.sql("UPDATE booking SET rescue_stay_request_id = :stay WHERE id = :id")
                .param("id", bookingId).param("stay", stayRequestId).update();
    }

    public Optional<UUID> rescueSearchOf(UUID bookingId) {
        return jdbc.sql("SELECT rescue_stay_request_id FROM booking WHERE id = :id").param("id", bookingId)
                .query(UUID.class).optional().filter(java.util.Objects::nonNull);
    }

    /** The booking keeps the UTM tags of the search it came from, so each reel link's bookings can be counted. */
    public void copyAttribution(UUID bookingId, UUID stayRequestId) {
        jdbc.sql("""
                        UPDATE booking b SET utm_source = q.utm_source, utm_medium = q.utm_medium,
                               utm_campaign = q.utm_campaign
                        FROM stay_request q WHERE b.id = :booking AND q.id = :stay""")
                .param("booking", bookingId).param("stay", stayRequestId).update();
    }

    public long promoDiscountPaise(UUID bookingId) {
        return jdbc.sql("SELECT promo_discount_paise FROM booking WHERE id = :id").param("id", bookingId)
                .query(Long.class).optional().orElse(0L);
    }

    /** The traveller ticked "Send my booking pass and updates on WhatsApp" (null clears it). */
    public void whatsappUpdates(UUID bookingId, Instant optedInAt) {
        jdbc.sql("UPDATE booking SET whatsapp_updates_at = :at WHERE id = :id").param("id", bookingId)
                .param("at", optedInAt == null ? null : at(optedInAt)).update();
    }

    public boolean wantsWhatsAppUpdates(UUID bookingId) {
        return jdbc.sql("SELECT whatsapp_updates_at IS NOT NULL FROM booking WHERE id = :id").param("id", bookingId)
                .query(Boolean.class).single();
    }

    public void hideUntil(UUID bookingId, Instant until) {
        jdbc.sql("UPDATE booking SET hide_until = :until WHERE id = :id").param("id", bookingId)
                .param("until", at(until)).update();
    }

    public void keepHidden(UUID bookingId, Instant now) {
        jdbc.sql("UPDATE booking SET keep_hidden_at = COALESCE(keep_hidden_at, :now) WHERE id = :id")
                .param("id", bookingId).param("now", at(now)).update();
    }

    /** When a turned-away dispute stops hiding its hotel (null: never set, or kept hidden). */
    public record Hiding(Instant hideUntil, Instant keepHiddenAt) {
    }

    public Hiding hiding(UUID bookingId) {
        return jdbc.sql("SELECT hide_until, keep_hidden_at FROM booking WHERE id = :id").param("id", bookingId)
                .query((rs, n) -> new Hiding(instant(rs, "hide_until"), instant(rs, "keep_hidden_at"))).single();
    }

    public void countHotelFailure(UUID hotelId) {
        jdbc.sql("UPDATE hotel SET reliability_failures = reliability_failures + 1 WHERE id = :id")
                .param("id", hotelId).update();
    }

    /** Ids of bookings in {@code status} whose {@code column} deadline is at or before {@code cutoff}. */
    public List<UUID> due(BookingStatus status, String column, Instant cutoff) {
        if (!Set.of("pay_by", "confirm_by", "arrive_by", "leave_at", "complete_by").contains(column)) {
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
        // Messages sent to the traveller carry their number too.
        jdbc.sql("""
                        UPDATE whatsapp_message SET wa_id = 'deleted', payload = '{}'::jsonb, updated_at = :now
                        WHERE audience = 'TRAVELLER' AND wa_id <> 'deleted' AND booking_id IN (
                            SELECT id FROM booking WHERE phone IS NOT NULL AND leave_at <= :cutoff)""")
                .param("cutoff", at(cutoff)).param("now", at(now)).update();
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
                instant(rs, "updated_at"), instant(rs, "arrival_check_sent_at"), instant(rs, "complete_by"),
                rs.getString("dispute_reason"), instant(rs, "disputed_at"), instant(rs, "settled_at"),
                rs.getObject("hold_id", UUID.class));
    }
}
