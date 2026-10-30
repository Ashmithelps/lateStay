package com.latestay.promo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.TestBookings;
import com.latestay.ledger.Ledger;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Promo codes: deposit 200, commission 100. The clock starts at 17:00Z on 11 Nov 2026. */
class PromoIT extends BookingItSupport {

    static final double LAT = 36.0;
    static final AtomicInteger NEXT = new AtomicInteger();

    @Autowired
    Promos promos;
    @Autowired
    Ledger ledger;

    String code() {
        return "PROMO" + System.nanoTime() % 1_000_000_000L + NEXT.incrementAndGet();
    }

    static String mobile() {
        return "9" + String.format("%09d", (System.nanoTime() + NEXT.incrementAndGet()) % 1_000_000_000L);
    }

    PromoCode promo(long discountPaise, int maxUses) {
        return promos.create(code(), discountPaise, clock.instant().minus(Duration.ofDays(1)),
                clock.instant().plus(Duration.ofDays(7)), maxUses);
    }

    long charged(BookingCreated created) {
        return jdbc.sql("SELECT amount FROM payment WHERE booking_id = :b").param("b", created.bookingId())
                .query(Long.class).single();
    }

    String redemption(BookingCreated created) {
        return jdbc.sql("SELECT status FROM promo_redemption WHERE booking_id = :b").param("b", created.bookingId())
                .query(String.class).single();
    }

    @Test
    void theDiscountComesOffTheDepositAndTheHotelIsStillOwedTheSame() {
        PromoCode promo = promo(5_000, 10);
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = fixtures.bookWithPromo(agreed, mobile(), promo.code().toLowerCase());

        assertThat(charged(created)).isEqualTo(150);
        assertThat(created.checkout().amountPaise()).isEqualTo(15_000);
        assertThat(redemption(created)).isEqualTo("RESERVED");
        fixtures.pay(created);
        assertThat(redemption(created)).isEqualTo("USED");
        bookings.confirm(created.bookingId());
        bookings.checkInByOwner(created.bookingId());

        assertThat(ledger.forBooking(created.bookingId())).extracting(e -> e.type() + " " + e.amountPaise())
                .containsExactlyInAnyOrder("DEPOSIT_COLLECTED 15000", "COMMISSION -10000", "PROMO_SUBSIDY 5000");
        // Without the code the hotel would be owed 200 - 100 = ₹100; it still is.
        assertThat(ledger.balance(agreed.hotel().id())).isEqualTo(10_000);
        // The desk amount doesn't change.
        assertThat(jdbc.sql("SELECT pay_at_desk FROM booking WHERE id = :b").param("b", created.bookingId())
                .query(Long.class).single()).isEqualTo(agreed.price() - 200);
    }

    @Test
    void aRefundAfterTheStayTakesBackTheHotelsWholeShare() {
        PromoCode promo = promo(5_000, 10);
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = fixtures.bookWithPromo(agreed, mobile(), promo.code());
        fixtures.pay(created);
        bookings.confirm(created.bookingId());
        bookings.checkInByOwner(created.bookingId());
        bookings.rescue(created.bookingId());

        assertThat(ledger.balance(agreed.hotel().id())).isZero();
        assertThat(redemption(created)).isEqualTo("RELEASED");
    }

    @Test
    void anExpiredOrNotYetValidCodeIsRefused() {
        PromoCode expired = promos.create(code(), 5_000, clock.instant().minus(Duration.ofDays(10)),
                clock.instant().minus(Duration.ofSeconds(1)), 10);
        PromoCode future = promos.create(code(), 5_000, clock.instant().plus(Duration.ofHours(1)),
                clock.instant().plus(Duration.ofDays(1)), 10);
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);

        assertThatThrownBy(() -> fixtures.bookWithPromo(agreed, mobile(), expired.code()))
                .isInstanceOf(PromoRejectedException.class).hasMessage("This code has expired.");
        assertThatThrownBy(() -> fixtures.bookWithPromo(agreed, mobile(), future.code()))
                .isInstanceOf(PromoRejectedException.class).hasMessage("This code isn't valid yet.");
        assertThatThrownBy(() -> fixtures.bookWithPromo(agreed, mobile(), "NOSUCHCODE"))
                .isInstanceOf(PromoRejectedException.class).hasMessage("That code isn't valid.");
        // Nothing was booked by the failed attempts.
        assertThat(jdbc.sql("SELECT count(*) FROM booking WHERE session_id = :s").param("s", agreed.sessionId())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aCodeIsUsedUpAfterItsTotalUses() {
        PromoCode promo = promo(5_000, 2);
        fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 3), mobile(), promo.code());
        fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 3), mobile(), promo.code());
        TestBookings.Agreed third = fixtures.agreed(LAT, 1400, 700, 3);
        assertThatThrownBy(() -> fixtures.bookWithPromo(third, mobile(), promo.code()))
                .isInstanceOf(PromoRejectedException.class).hasMessage("This code has been used up.");
    }

    @Test
    void onePhoneCanUseACodeOnce() {
        PromoCode promo = promo(5_000, 10);
        String phone = mobile();
        BookingCreated first = fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 3), phone, promo.code());
        fixtures.pay(first);
        TestBookings.Agreed again = fixtures.agreed(LAT, 1400, 700, 3);
        assertThatThrownBy(() -> fixtures.bookWithPromo(again, phone, promo.code()))
                .isInstanceOf(PromoRejectedException.class).hasMessage("You've already used this code.");
        // Another phone still can.
        assertThat(charged(fixtures.bookWithPromo(again, mobile(), promo.code()))).isEqualTo(150);
    }

    @Test
    void anUnpaidExpiryOrADeclineGivesTheUseBack() {
        PromoCode promo = promo(5_000, 1);
        String phone = mobile();
        BookingCreated unpaid = fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 3), phone, promo.code());
        clock.advance(Duration.ofMinutes(11));
        jobs.expireUnpaid();
        assertThat(statusOf(unpaid.bookingId())).isEqualTo(BookingStatus.EXPIRED);
        assertThat(redemption(unpaid)).isEqualTo("RELEASED");

        BookingCreated declined = fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 3), phone, promo.code());
        fixtures.pay(declined);
        bookings.decline(declined.bookingId());
        assertThat(redemption(declined)).isEqualTo("RELEASED");
        // The single use is free again, for the same phone too.
        assertThat(charged(fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 3), phone, promo.code())))
                .isEqualTo(150);
    }

    @Test
    void atLeastOneRupeeIsAlwaysPaidOnline() {
        PromoCode big = promo(50_000, 10); // ₹500 off a ₹200 deposit
        BookingCreated created = fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 3), mobile(), big.code());
        assertThat(charged(created)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT promo_discount_paise FROM booking WHERE id = :b").param("b", created.bookingId())
                .query(Long.class).single()).isEqualTo(19_900);
    }

    @Test
    void addingACodeBeforePayingReplacesTheUnpaidBooking() {
        PromoCode promo = promo(5_000, 10);
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        String phone = mobile();
        BookingCreated plain = fixtures.bookWithPromo(agreed, phone, null);
        BookingCreated withCode = fixtures.bookWithPromo(agreed, phone, promo.code());
        assertThat(withCode.bookingId()).isNotEqualTo(plain.bookingId());
        assertThat(statusOf(plain.bookingId())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(charged(withCode)).isEqualTo(150);
        // Same code again: the same booking comes back.
        assertThat(fixtures.bookWithPromo(agreed, phone, promo.code()).bookingId()).isEqualTo(withCode.bookingId());
    }

    @Test
    void theBookScreenPreviewsTheDiscount() throws Exception {
        PromoCode promo = promo(5_000, 10);
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        mvc.perform(get("/api/negotiations/{id}/promo", agreed.sessionId()).param("code", " " + promo.code().toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discount").value(50))
                .andExpect(jsonPath("$.payNow").value(150));
        mvc.perform(get("/api/negotiations/{id}/promo", agreed.sessionId()).param("code", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMO_UNKNOWN"));
    }

    @Test
    void adminCreatesListsAndDeactivatesCodes() throws Exception {
        String c = code();
        String body = """
                {"code": "%s", "discountPaise": 2550, "validFrom": "2026-11-01T00:00:00Z",
                 "validUntil": "2026-12-01T00:00:00Z", "maxUses": 50}""".formatted(c);
        mvc.perform(post("/api/admin/promo-codes").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("whole number")));
        String id = json(mvc.perform(post("/api/admin/promo-codes").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("2550", "5000")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/admin/promo-codes/{id}/deactivate", id).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(jsonPath("$.active").value(false));
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        assertThatThrownBy(() -> fixtures.bookWithPromo(agreed, mobile(), c)).isInstanceOf(PromoRejectedException.class);
    }

    static String json(String body) {
        return body.replaceAll(".*\"id\":\"([0-9a-f-]{36})\".*", "$1");
    }
}
