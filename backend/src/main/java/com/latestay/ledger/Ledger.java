package com.latestay.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What LateStay owes each hotel, as an append-only ledger in paise. The booking rules:
 *
 * <ul>
 *   <li>The guest stayed or didn't turn up (CHECKED_IN, COMPLETED or NO_SHOW, whichever comes first): the hotel is
 *       owed the deposit minus LateStay's commission (DEPOSIT_COLLECTED, COMMISSION).
 *   <li>Any refund after that, or HOTEL_FAILED: the hotel's share is taken back (REFUND), so nothing is owed.
 *   <li>Declined, timed out, cancelled or failed before the stay: nothing was ever written, so nothing is owed.
 * </ul>
 *
 * Each of those is written at most once per booking (a unique index), so a replayed transition can't double-count.
 * Called inside the booking's transaction.
 */
@Component
public class Ledger {

    private final JdbcClient jdbc;
    private final Clock clock;

    public Ledger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The hotel earned its share of this booking's deposit (rupee amounts, as on the booking). */
    public void owed(UUID hotelId, UUID bookingId, long depositRupees, long commissionRupees) {
        owed(hotelId, bookingId, depositRupees, commissionRupees, 0);
    }

    /**
     * As above, for a deposit reduced by a promo code: LateStay pays the hotel the discount (PROMO_SUBSIDY), so the
     * hotel is owed the same as without the code.
     *
     * @param depositRupees what the traveller actually paid online
     */
    public void owed(UUID hotelId, UUID bookingId, long depositRupees, long commissionRupees, long promoPaise) {
        once(hotelId, bookingId, LedgerEntryType.DEPOSIT_COLLECTED, paise(depositRupees), "deposit paid online");
        once(hotelId, bookingId, LedgerEntryType.COMMISSION, -paise(commissionRupees), "LateStay commission");
        if (promoPaise > 0) {
            once(hotelId, bookingId, LedgerEntryType.PROMO_SUBSIDY, promoPaise, "promo code paid by LateStay");
        }
    }

    /** The deposit went back to the traveller: undo the hotel's share, if it was ever owed. */
    public void refunded(UUID bookingId, String why) {
        record Owed(UUID hotelId, long net) {
        }
        List<Owed> owed = jdbc.sql("""
                        SELECT hotel_id, sum(amount_paise) AS net FROM ledger_entry
                        WHERE booking_id = :b AND type IN ('DEPOSIT_COLLECTED', 'COMMISSION', 'PROMO_SUBSIDY')
                        GROUP BY hotel_id""")
                .param("b", bookingId)
                .query((rs, n) -> new Owed(rs.getObject("hotel_id", UUID.class), rs.getLong("net"))).list();
        for (Owed o : owed) {
            once(o.hotelId(), bookingId, LedgerEntryType.REFUND, -o.net(), why);
        }
    }

    /** A manual correction by an admin, settled with the next payout. */
    public LedgerEntry adjust(UUID hotelId, long amountPaise, String note) {
        return insert(hotelId, null, null, LedgerEntryType.ADJUSTMENT, amountPaise, note);
    }

    LedgerEntry payout(UUID hotelId, UUID settlementId, long amountPaise, String upiReference) {
        return insert(hotelId, null, settlementId, LedgerEntryType.PAYOUT, -amountPaise, "UPI " + upiReference);
    }

    /** Sum of every entry: what LateStay owes the hotel right now. */
    public long balance(UUID hotelId) {
        return jdbc.sql("SELECT COALESCE(sum(amount_paise), 0) FROM ledger_entry WHERE hotel_id = :h")
                .param("h", hotelId).query(Long.class).single();
    }

    /** Earnings (everything but payouts) recorded from {@code from} on. */
    public long earnedSince(UUID hotelId, Instant from) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(amount_paise), 0) FROM ledger_entry
                        WHERE hotel_id = :h AND type <> 'PAYOUT' AND created_at >= :from""")
                .param("h", hotelId).param("from", at(from)).query(Long.class).single();
    }

    public List<LedgerEntry> forBooking(UUID bookingId) {
        return jdbc.sql("SELECT * FROM ledger_entry WHERE booking_id = :b ORDER BY created_at, type")
                .param("b", bookingId).query(Ledger::map).list();
    }

    public List<LedgerEntry> forHotel(UUID hotelId) {
        return jdbc.sql("SELECT * FROM ledger_entry WHERE hotel_id = :h ORDER BY created_at, type")
                .param("h", hotelId).query(Ledger::map).list();
    }

    public static long paise(long rupees) {
        return Math.multiplyExact(rupees, 100L);
    }

    private void once(UUID hotelId, UUID bookingId, LedgerEntryType type, long amountPaise, String note) {
        jdbc.sql("""
                        INSERT INTO ledger_entry (id, hotel_id, booking_id, type, amount_paise, note, created_at)
                        VALUES (:id, :h, :b, :type, :amount, :note, :now)
                        ON CONFLICT (booking_id, type)
                            WHERE type IN ('DEPOSIT_COLLECTED', 'COMMISSION', 'REFUND', 'PROMO_SUBSIDY') DO NOTHING""")
                .param("id", UUID.randomUUID()).param("h", hotelId).param("b", bookingId).param("type", type.name())
                .param("amount", amountPaise).param("note", note).param("now", at(clock.instant())).update();
    }

    private LedgerEntry insert(UUID hotelId, UUID bookingId, UUID settlementId, LedgerEntryType type,
            long amountPaise, String note) {
        LedgerEntry entry = new LedgerEntry(UUID.randomUUID(), hotelId, bookingId, settlementId, type, amountPaise,
                note, clock.instant());
        jdbc.sql("""
                        INSERT INTO ledger_entry (id, hotel_id, booking_id, settlement_id, type, amount_paise, note,
                                                  created_at)
                        VALUES (:id, :h, :b, :s, :type, :amount, :note, :now)""")
                .param("id", entry.id()).param("h", hotelId).param("b", bookingId).param("s", settlementId)
                .param("type", type.name()).param("amount", amountPaise).param("note", note)
                .param("now", at(entry.createdAt())).update();
        return entry;
    }

    static LedgerEntry map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new LedgerEntry(rs.getObject("id", UUID.class), rs.getObject("hotel_id", UUID.class),
                rs.getObject("booking_id", UUID.class), rs.getObject("settlement_id", UUID.class),
                LedgerEntryType.valueOf(rs.getString("type")), rs.getLong("amount_paise"), rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
