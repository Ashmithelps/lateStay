package com.latestay.ledger;

import com.latestay.hotel.ConflictException;
import com.latestay.hotel.NotFoundException;
import com.latestay.stay.LateStayTime;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly settlements. Each Monday, every hotel's ledger entries from before that Monday (00:00 Asia/Kolkata) that
 * no settlement covers yet become one settlement for the week before, if they add up to more than zero. Entries
 * of a booking that is in dispute wait for a later week, and so does a week that comes to zero or less.
 */
@Service
public class Settlements {

    /** One settlement's line, for the admin panel, the owner page and the CSV. */
    public record Line(UUID entryId, LedgerEntryType type, long amountPaise, String note, Instant createdAt,
            String bookingCode, LocalDate nightOf, String guestName) {
    }

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final Clock clock;

    public Settlements(JdbcClient jdbc, Ledger ledger, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** The Monday on or before today (Asia/Kolkata): settlements made today cover the week before it. */
    public LocalDate thisMonday() {
        return LocalDate.ofInstant(clock.instant(), LateStayTime.ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** When the next settlement is made: the next Monday after today, or today if it's Monday before 10:00. */
    public LocalDate nextPayoutDate() {
        var now = java.time.LocalDateTime.ofInstant(clock.instant(), LateStayTime.ZONE);
        LocalDate today = now.toLocalDate();
        if (today.getDayOfWeek() == DayOfWeek.MONDAY && now.getHour() < 10) {
            return today;
        }
        return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    /** Makes this week's settlements. Running it again the same week makes none. @return how many were made */
    @Transactional
    public int settleWeek() {
        LocalDate monday = thisMonday();
        LocalDate periodStart = monday.minusWeeks(1);
        Instant cutoff = monday.atStartOfDay(LateStayTime.ZONE).toInstant();
        record Due(UUID hotelId, long total) {
        }
        List<Due> due = jdbc.sql("""
                        SELECT e.hotel_id, sum(e.amount_paise) AS total
                        FROM ledger_entry e
                        LEFT JOIN booking b ON b.id = e.booking_id
                        WHERE e.type <> 'PAYOUT' AND e.created_at < :cutoff
                          AND NOT EXISTS (SELECT 1 FROM settlement_line l WHERE l.ledger_entry_id = e.id)
                          AND (b.id IS NULL OR b.status <> 'DISPUTED')
                          AND NOT EXISTS (SELECT 1 FROM settlement s
                                          WHERE s.hotel_id = e.hotel_id AND s.period_start = :start)
                        GROUP BY e.hotel_id
                        HAVING sum(e.amount_paise) > 0""")
                .param("cutoff", Ledger.at(cutoff)).param("start", periodStart)
                .query((rs, n) -> new Due(rs.getObject("hotel_id", UUID.class), rs.getLong("total"))).list();
        for (Due d : due) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO settlement (id, hotel_id, period_start, period_end, amount_paise, status,
                                                    created_at)
                            VALUES (:id, :h, :start, :end, :amount, 'DUE', :now)""")
                    .param("id", id).param("h", d.hotelId()).param("start", periodStart)
                    .param("end", monday.minusDays(1)).param("amount", d.total())
                    .param("now", Ledger.at(clock.instant())).update();
            jdbc.sql("""
                            INSERT INTO settlement_line (settlement_id, ledger_entry_id)
                            SELECT :id, e.id FROM ledger_entry e
                            LEFT JOIN booking b ON b.id = e.booking_id
                            WHERE e.hotel_id = :h AND e.type <> 'PAYOUT' AND e.created_at < :cutoff
                              AND NOT EXISTS (SELECT 1 FROM settlement_line l WHERE l.ledger_entry_id = e.id)
                              AND (b.id IS NULL OR b.status <> 'DISPUTED')""")
                    .param("id", id).param("h", d.hotelId()).param("cutoff", Ledger.at(cutoff)).update();
        }
        return due.size();
    }

    /** The admin paid it by UPI: PAID, and a PAYOUT entry. Repeating it with the same reference changes nothing. */
    @Transactional
    public Settlement markPaid(UUID id, String upiReference) {
        String reference = upiReference == null ? "" : upiReference.trim();
        if (reference.isEmpty()) {
            throw new IllegalArgumentException("enter the UPI reference");
        }
        Settlement s = jdbc.sql(SELECT + " WHERE s.id = :id FOR UPDATE OF s").param("id", id)
                .query(Settlements::map).optional().orElseThrow(() -> new NotFoundException("settlement", id));
        if (s.status().equals("PAID")) {
            if (s.upiReference().equals(reference)) {
                return s;
            }
            throw new ConflictException("this settlement was already paid with UPI reference " + s.upiReference());
        }
        jdbc.sql("UPDATE settlement SET status = 'PAID', upi_reference = :ref, paid_at = :now WHERE id = :id")
                .param("id", id).param("ref", reference).param("now", Ledger.at(clock.instant())).update();
        ledger.payout(s.hotelId(), id, s.amountPaise(), reference);
        return find(id).orElseThrow();
    }

    public Optional<Settlement> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id").param("id", id).query(Settlements::map).optional();
    }

    /** Newest first; status DUE or PAID, or null for both. */
    public List<Settlement> list(String status, int limit) {
        return jdbc.sql(SELECT + " WHERE (CAST(:status AS text) IS NULL OR s.status = :status) "
                        + "ORDER BY s.period_start DESC, h.name LIMIT :limit")
                .param("status", status).param("limit", limit).query(Settlements::map).list();
    }

    public List<Settlement> forHotel(UUID hotelId, int limit) {
        return jdbc.sql(SELECT + " WHERE s.hotel_id = :h ORDER BY s.period_start DESC LIMIT :limit")
                .param("h", hotelId).param("limit", limit).query(Settlements::map).list();
    }

    public List<Line> lines(UUID settlementId) {
        return jdbc.sql("""
                        SELECT e.id, e.type, e.amount_paise, e.note, e.created_at, b.code, b.night_of, b.guest_name
                        FROM settlement_line l
                        JOIN ledger_entry e ON e.id = l.ledger_entry_id
                        LEFT JOIN booking b ON b.id = e.booking_id
                        WHERE l.settlement_id = :id
                        ORDER BY b.night_of NULLS LAST, b.code, e.created_at, e.type""")
                .param("id", settlementId)
                .query((rs, n) -> new Line(rs.getObject("id", UUID.class),
                        LedgerEntryType.valueOf(rs.getString("type")), rs.getLong("amount_paise"),
                        rs.getString("note"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getString("code"), rs.getObject("night_of", LocalDate.class), rs.getString("guest_name")))
                .list();
    }

    /**
     * One row per line: night, booking code, guest initials (not the full name), type, amount in rupees, note,
     * then a total row.
     */
    public String csv(UUID settlementId) {
        Settlement s = find(settlementId).orElseThrow(() -> new NotFoundException("settlement", settlementId));
        StringBuilder out = new StringBuilder("night,booking_code,guest,type,amount_inr,note\r\n");
        for (Line line : lines(settlementId)) {
            out.append(line.nightOf() == null ? "" : line.nightOf()).append(',')
                    .append(cell(line.bookingCode())).append(',')
                    .append(cell(initials(line.guestName()))).append(',')
                    .append(line.type()).append(',')
                    .append(rupees(line.amountPaise())).append(',')
                    .append(cell(line.note())).append("\r\n");
        }
        out.append(",,,TOTAL,").append(rupees(s.amountPaise())).append(",")
                .append(cell(s.hotelName() + " " + s.periodStart() + " to " + s.periodEnd())).append("\r\n");
        return out.toString();
    }

    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            out.append(Character.toUpperCase(part.charAt(0))).append('.');
        }
        return out.toString();
    }

    /** 12345 paise -> "123.45" */
    static String rupees(long paise) {
        return java.math.BigDecimal.valueOf(paise, 2).toPlainString();
    }

    /** Quoted when needed; a leading =, +, - or @ is defused so spreadsheets don't run it as a formula. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static final String SELECT = """
            SELECT s.id, s.hotel_id, h.name AS hotel_name, s.period_start, s.period_end, s.amount_paise, s.status,
                   s.upi_reference, s.paid_at, s.created_at
            FROM settlement s JOIN hotel h ON h.id = s.hotel_id""";

    private static Settlement map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        var paid = rs.getObject("paid_at", java.time.OffsetDateTime.class);
        return new Settlement(rs.getObject("id", UUID.class), rs.getObject("hotel_id", UUID.class),
                rs.getString("hotel_name"), rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class), rs.getLong("amount_paise"), rs.getString("status"),
                rs.getString("upi_reference"), paid == null ? null : paid.toInstant(),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
    }
}
