package com.latestay.promo;

import com.latestay.booking.BookingStatus;
import com.latestay.booking.BookingStatusChanged;
import com.latestay.hotel.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promo codes: a flat discount off the deposit (whole rupees), valid between two dates, for a number of uses in
 * total and once per verified phone. A use is RESERVED when the booking is made, USED once it's paid, and
 * RELEASED (given back) if the booking expires unpaid or its deposit is refunded. The traveller always pays at
 * least ₹1 online. LateStay pays the discount to the hotel (ledger PROMO_SUBSIDY), so the hotel's payout never
 * shrinks.
 */
@Service
public class Promos {

    public static final long MIN_PAYABLE_RUPEES = 1;

    /** Booking states that end a booking with its deposit back (or never paid): the use is given back. */
    private static final Set<BookingStatus> RELEASES = Set.of(BookingStatus.EXPIRED, BookingStatus.CANCELLED,
            BookingStatus.HOTEL_DECLINED, BookingStatus.HOTEL_TIMEOUT, BookingStatus.REFUNDED,
            BookingStatus.HOTEL_FAILED);

    /** What a code takes off a deposit. */
    public record Applied(PromoCode promo, long discountRupees, long payNowRupees) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    Promos(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** Checks dates and uses (not the phone, which is only known at booking) and works out the discount. */
    public Applied preview(String code, long depositRupees) {
        PromoCode promo = find(normalise(code)).orElseThrow(Promos::unknown);
        check(promo);
        return apply(promo, depositRupees);
    }

    /**
     * Reserves one use for this booking, checking every rule under a lock on the code (so its last use can't go
     * twice). Call inside the booking's transaction, after the booking row exists.
     */
    @Transactional
    public Applied reserve(String code, UUID bookingId, String phoneHash, long depositRupees) {
        PromoCode promo = lock(normalise(code)).orElseThrow(Promos::unknown);
        check(promo);
        boolean usedByPhone = jdbc.sql("""
                        SELECT count(*) > 0 FROM promo_redemption
                        WHERE promo_id = :p AND phone_hash = :phone AND status <> 'RELEASED'""")
                .param("p", promo.id()).param("phone", phoneHash).query(Boolean.class).single();
        if (usedByPhone) {
            throw new PromoRejectedException("PROMO_USED_BY_PHONE", "You've already used this code.");
        }
        Applied applied = apply(promo, depositRupees);
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO promo_redemption (id, promo_id, booking_id, phone_hash, discount_paise, status,
                                                      created_at, updated_at)
                        VALUES (:id, :p, :b, :phone, :discount, 'RESERVED', :now, :now)""")
                .param("id", UUID.randomUUID()).param("p", promo.id()).param("b", bookingId).param("phone", phoneHash)
                .param("discount", applied.discountRupees() * 100).param("now", at(now)).update();
        return applied;
    }

    /** The code a booking used, if any. */
    public Optional<String> codeOf(UUID bookingId) {
        return jdbc.sql("""
                        SELECT c.code FROM promo_redemption r JOIN promo_code c ON c.id = r.promo_id
                        WHERE r.booking_id = :b""")
                .param("b", bookingId).query(String.class).optional();
    }

    @EventListener
    void on(BookingStatusChanged change) {
        String status = change.to() == BookingStatus.DEPOSIT_PAID ? "USED"
                : RELEASES.contains(change.to()) ? "RELEASED" : null;
        if (status == null) {
            return;
        }
        jdbc.sql("""
                        UPDATE promo_redemption SET status = :status, updated_at = :now
                        WHERE booking_id = :b AND status <> 'RELEASED'""")
                .param("status", status).param("now", at(clock.instant())).param("b", change.booking().id()).update();
    }

    // Admin

    @Transactional
    public PromoCode create(String code, long discountPaise, Instant validFrom, Instant validUntil, int maxUses) {
        String c = normalise(code);
        if (!c.matches("[A-Z0-9]{3,20}")) {
            throw new PromoRejectedException("PROMO_INVALID", "Codes are 3 to 20 letters or digits.");
        }
        if (discountPaise <= 0 || discountPaise % 100 != 0) {
            throw new PromoRejectedException("PROMO_INVALID", "The discount must be a whole number of rupees.");
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new PromoRejectedException("PROMO_INVALID", "The end date must be after the start date.");
        }
        if (maxUses <= 0) {
            throw new PromoRejectedException("PROMO_INVALID", "Allow at least one use.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO promo_code (id, code, discount_paise, valid_from, valid_until, max_uses, created_at)
                        VALUES (:id, :code, :d, :from, :until, :max, :now)""")
                .param("id", id).param("code", c).param("d", discountPaise).param("from", at(validFrom))
                .param("until", at(validUntil)).param("max", maxUses).param("now", at(clock.instant())).update();
        return find(c).orElseThrow();
    }

    @Transactional
    public PromoCode deactivate(UUID id) {
        String code = jdbc.sql("UPDATE promo_code SET active = false WHERE id = :id RETURNING code").param("id", id)
                .query(String.class).optional().orElseThrow(() -> new NotFoundException("promo code", id));
        return find(code).orElseThrow();
    }

    public List<PromoCode> list() {
        return jdbc.sql(SELECT + " GROUP BY c.id ORDER BY c.created_at DESC").query(Promos::map).list();
    }

    // Rules

    private void check(PromoCode promo) {
        Instant now = clock.instant();
        if (!promo.active()) {
            throw unknown();
        }
        if (now.isBefore(promo.validFrom())) {
            throw new PromoRejectedException("PROMO_NOT_STARTED", "This code isn't valid yet.");
        }
        if (!now.isBefore(promo.validUntil())) {
            throw new PromoRejectedException("PROMO_EXPIRED", "This code has expired.");
        }
        if (promo.uses() >= promo.maxUses()) {
            throw new PromoRejectedException("PROMO_USED_UP", "This code has been used up.");
        }
    }

    /** The discount, capped so at least ₹1 is still paid online. */
    static Applied apply(PromoCode promo, long depositRupees) {
        long discount = Math.min(promo.discountPaise() / 100, Math.max(0, depositRupees - MIN_PAYABLE_RUPEES));
        return new Applied(promo, discount, depositRupees - discount);
    }

    private static PromoRejectedException unknown() {
        return new PromoRejectedException("PROMO_UNKNOWN", "That code isn't valid.");
    }

    private Optional<PromoCode> find(String code) {
        return jdbc.sql(SELECT + " WHERE c.code = :code GROUP BY c.id").param("code", code).query(Promos::map)
                .optional();
    }

    private Optional<PromoCode> lock(String code) {
        Optional<UUID> id = jdbc.sql("SELECT id FROM promo_code WHERE code = :code FOR UPDATE").param("code", code)
                .query(UUID.class).optional();
        return id.flatMap(i -> find(code));
    }

    private static final String SELECT = """
            SELECT c.id, c.code, c.discount_paise, c.valid_from, c.valid_until, c.max_uses, c.active, c.created_at,
                   count(r.id) FILTER (WHERE r.status <> 'RELEASED') AS uses
            FROM promo_code c LEFT JOIN promo_redemption r ON r.promo_id = c.id""";

    private static PromoCode map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PromoCode(rs.getObject("id", UUID.class), rs.getString("code"), rs.getLong("discount_paise"),
                rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                rs.getObject("valid_until", OffsetDateTime.class).toInstant(), rs.getInt("max_uses"),
                rs.getBoolean("active"), rs.getInt("uses"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
