package com.latestay.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.FreshDatabase;
import com.latestay.TestClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The morning digest against a fixed dataset in a database of its own, so every number is exact. Night of
 * 11 Nov 2026; the window is 11 Nov 09:00 to 12 Nov 09:00 India time (03:30Z to 03:30Z).
 */
class DigestIT {

    static final LocalDate NIGHT = LocalDate.of(2026, 11, 11);
    static final Instant IN_WINDOW = Instant.parse("2026-11-11T20:00:00Z");
    static final Instant BEFORE_WINDOW = Instant.parse("2026-11-10T20:00:00Z");
    static final AtomicInteger CODE = new AtomicInteger(1000);

    static FreshDatabase db;
    static JdbcClient jdbc;
    static UUID pine;
    static UUID cedar;
    static UUID room;

    @BeforeAll
    static void dataset() {
        db = FreshDatabase.create("digest");
        jdbc = db.jdbc;
        pine = hotel("Pine Crest", true);
        cedar = hotel("Cedar Loft", true);
        for (String name : List.of("Deodar Nest", "Moss Rooms", "Zeta Inn", "Apple Stay")) {
            hotel(name, true);
        }
        hotel("Old Lodge", false); // inactive: never listed
        answered(pine, NIGHT);
        answered(cedar, NIGHT);
        room = jdbc.sql("INSERT INTO room_type (hotel_id, name, list_price, max_guests) VALUES (:h, 'Double', 1400, 2) "
                + "RETURNING id").param("h", pine).query(UUID.class).single();

        // Night of 11 Nov.
        UUID completed = booking(pine, NIGHT, "COMPLETED", 200, 0, true, true);
        owed(pine, completed, 20_000, 0);
        UUID checkedIn = booking(pine, NIGHT, "CHECKED_IN", 150, 5_000, true, true); // ₹50 promo
        owed(pine, checkedIn, 15_000, 5_000);
        UUID noShow = booking(pine, NIGHT, "NO_SHOW", 200, 0, true, false);
        owed(pine, noShow, 20_000, 0);
        UUID rescued = booking(pine, NIGHT, "HOTEL_FAILED", 200, 0, true, true);
        owed(pine, rescued, 20_000, 0);
        ledger(pine, rescued, "REFUND", -10_000);
        payment(rescued, 200, "REFUNDED", IN_WINDOW);
        UUID declined = booking(pine, NIGHT, "REFUNDED", 200, 0, true, false);
        payment(declined, 200, "REFUNDED", IN_WINDOW);
        booking(pine, NIGHT, "PENDING_PAYMENT", 200, 0, false, false); // unpaid: not a booking yet
        booking(cedar, NIGHT, "DISPUTED", 200, 0, true, false);

        // The night before: only the open dispute and the ledger totals count.
        booking(cedar, NIGHT.minusDays(1), "DISPUTED", 200, 0, true, false);
        UUID oldRefund = booking(cedar, NIGHT.minusDays(1), "REFUNDED", 200, 0, true, false);
        payment(oldRefund, 200, "REFUNDED", BEFORE_WINDOW);
        jdbc.sql("INSERT INTO ledger_entry (id, hotel_id, type, amount_paise, note, created_at) "
                        + "VALUES (:id, :h, 'ADJUSTMENT', -2500, 'towel', now())")
                .param("id", UUID.randomUUID()).param("h", cedar).update();

        // LLM: $1.20 + $0.30 inside the window, $5 outside.
        UUID session = jdbc.sql("SELECT session_id FROM booking WHERE id = :b").param("b", completed)
                .query(UUID.class).single();
        turn(session, 1, 1_200_000, IN_WINDOW);
        turn(session, 2, 300_000, IN_WINDOW.plusSeconds(60));
        turn(session, 3, 5_000_000, BEFORE_WINDOW);
    }

    @Test
    void everyNumberMatchesTheDataset() {
        TestClock clock = new TestClock(Instant.parse("2026-11-12T03:30:00Z")); // 12 Nov 09:00 IST
        DigestService service = new DigestService(jdbc, clock, new BigDecimal("88"));
        assertThat(service.lastNight()).isEqualTo(NIGHT);

        Digest d = service.forNight(NIGHT);
        assertThat(d.bookings()).isEqualTo(6);
        assertThat(d.checkedIn()).isEqualTo(3);
        assertThat(d.completed()).isEqualTo(1);
        assertThat(d.noShows()).isEqualTo(1);
        assertThat(d.rescues()).isEqualTo(1);
        assertThat(d.openDisputes()).isEqualTo(2);
        assertThat(d.refunds()).isEqualTo(2);
        assertThat(d.refundsPaise()).isEqualTo(40_000);
        // ₹100 + (₹100 − ₹50 promo) + ₹100; the rescued booking was refunded, so it earns nothing.
        assertThat(d.commissionPaise()).isEqualTo(25_000);
        // ₹100 + ₹100 + ₹100 + ₹0 owed to Pine Crest, −₹25 adjustment at Cedar Loft.
        assertThat(d.owedToHotelsPaise()).isEqualTo(27_500);
        assertThat(d.noAnswer()).containsExactly("Apple Stay", "Deodar Nest", "Moss Rooms", "Zeta Inn");
        assertThat(d.llmCostMicroUsd()).isEqualTo(1_500_000);
        assertThat(d.llmCostPaise()).isEqualTo(13_200); // $1.50 × 88 = ₹132

        assertThat(MorningDigest.values(d)).containsExactly("Wed 11 Nov", "6", "3", "1", "1", "1", "2", "2", "400",
                "250", "275", "Apple Stay, Deodar Nest, Moss Rooms +1 more", "132");
    }

    @Test
    void beforeNineTheLatestCompleteNightIsTheOneBefore() {
        TestClock clock = new TestClock(Instant.parse("2026-11-12T03:29:00Z")); // 08:59 IST
        assertThat(new DigestService(jdbc, clock, BigDecimal.ONE).lastNight()).isEqualTo(NIGHT.minusDays(1));
    }

    @Test
    void amountsAndHotelListsFormatForTheTemplate() {
        assertThat(MorningDigest.rupees(110_000)).isEqualTo("1,100");
        assertThat(MorningDigest.rupees(2_550)).isEqualTo("25.50");
        assertThat(MorningDigest.hotels(List.of())).isEqualTo("none");
        assertThat(MorningDigest.hotels(List.of("A", "B"))).isEqualTo("A, B");
    }

    // Fixture

    static UUID hotel(String name, boolean active) {
        return jdbc.sql("""
                        INSERT INTO hotel (name, slug, town, address, location, whatsapp_number, active)
                        VALUES (:name, :slug, 'Manali', 'Mall Road', ST_SetSRID(ST_MakePoint(77.19, 32.24), 4326)::geography,
                                '+91 90000 00001', :active)
                        RETURNING id""")
                .param("name", name).param("slug", name.toLowerCase().replace(' ', '-')).param("active", active)
                .query(UUID.class).single();
    }

    static void answered(UUID hotel, LocalDate night) {
        jdbc.sql("INSERT INTO nightly_availability (hotel_id, night_of, rooms_free, source) VALUES (:h, :n, 3, 'WHATSAPP')")
                .param("h", hotel).param("n", night).update();
    }

    /** A booking (with its stay request and session) for the night, arriving 23:30. Rupee amounts. */
    static UUID booking(UUID hotel, LocalDate night, String status, long deposit, long promoPaise, boolean paid,
            boolean checkedIn) {
        OffsetDateTime arrive = night.atTime(23, 30).atOffset(ZoneOffset.ofHoursMinutes(5, 30));
        UUID roomType = hotel.equals(pine) ? room : jdbc.sql(
                "INSERT INTO room_type (hotel_id, name, list_price, max_guests) VALUES (:h, 'Room', 1400, 2) RETURNING id")
                .param("h", hotel).query(UUID.class).single();
        UUID stay = jdbc.sql("""
                        INSERT INTO stay_request (location, arrive_at, leave_at, guests)
                        VALUES (ST_SetSRID(ST_MakePoint(77.19, 32.24), 4326)::geography, :a, :l, 2) RETURNING id""")
                .param("a", arrive).param("l", arrive.plusHours(9).plusMinutes(30)).query(UUID.class).single();
        UUID session = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO negotiation_session (id, stay_request_id, room_type_id, status, current_offer,
                               agreed_price, list_price, floor_price, slot_type, max_rounds, round_to, arrival_time,
                               expires_at)
                        VALUES (:id, :stay, :room, 'AGREED', 1300, 1300, 1400, 700, 'LATE_NIGHT', 5, 50, '23:30',
                                now())""")
                .param("id", session).param("stay", stay).param("room", roomType).update();
        UUID id = UUID.randomUUID();
        long agreed = 1300;
        jdbc.sql("""
                        INSERT INTO booking (id, session_id, hotel_id, room_type_id, night_of, guest_name, phone, guests,
                               arrive_at, arrive_by, leave_at, agreed_price, deposit_amount, pay_at_desk,
                               commission_amount, code, status, pay_by, paid_at, checked_in_at, created_at,
                               updated_at, promo_discount_paise)
                        VALUES (:id, :session, :hotel, :room, :night, 'Asha Verma', '+919876543210', 2, :arrive,
                                :arrive, :leave, :agreed, :deposit, :desk, 100, :code, :status, :arrive, :paid,
                                :checkedIn, :arrive, :arrive, :promo)""")
                .param("id", id).param("session", session).param("hotel", hotel).param("room", roomType)
                .param("night", night).param("arrive", arrive).param("leave", arrive.plusHours(9).plusMinutes(30))
                .param("agreed", agreed).param("deposit", deposit).param("desk", agreed - deposit - promoPaise / 100)
                .param("code", String.valueOf(CODE.incrementAndGet())).param("status", status)
                .param("paid", paid ? arrive : null).param("checkedIn", checkedIn ? arrive : null)
                .param("promo", promoPaise).update();
        return id;
    }

    static void owed(UUID hotel, UUID booking, long depositPaise, long promoPaise) {
        ledger(hotel, booking, "DEPOSIT_COLLECTED", depositPaise);
        ledger(hotel, booking, "COMMISSION", -10_000);
        if (promoPaise > 0) {
            ledger(hotel, booking, "PROMO_SUBSIDY", promoPaise);
        }
    }

    static void ledger(UUID hotel, UUID booking, String type, long paise) {
        jdbc.sql("""
                        INSERT INTO ledger_entry (id, hotel_id, booking_id, type, amount_paise, created_at)
                        VALUES (:id, :h, :b, :type, :amount, now())""")
                .param("id", UUID.randomUUID()).param("h", hotel).param("b", booking).param("type", type)
                .param("amount", paise).update();
    }

    static void payment(UUID booking, long amount, String status, Instant updated) {
        jdbc.sql("""
                        INSERT INTO payment (booking_id, razorpay_order_id, amount, status, created_at, updated_at)
                        VALUES (:b, :order, :amount, :status, :at, :at)""")
                .param("b", booking).param("order", "order_" + booking).param("amount", amount)
                .param("status", status).param("at", updated.atOffset(ZoneOffset.UTC)).update();
    }

    static void turn(UUID session, int seq, long costMicroUsd, Instant at) {
        jdbc.sql("""
                        INSERT INTO negotiation_turn (session_id, seq, role, text, llm_calls, input_tokens, output_tokens,
                                                      model, llm_cost_micro_usd, created_at)
                        VALUES (:s, :seq, 'BOT', 'reply', 2, 1000, 200, 'test-model', :cost, :at)""")
                .param("s", session).param("seq", seq).param("cost", costMicroUsd)
                .param("at", at.atOffset(ZoneOffset.UTC)).update();
    }
}
