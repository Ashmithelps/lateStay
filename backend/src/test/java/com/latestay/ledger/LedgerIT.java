package com.latestay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.DisputeOutcome;
import com.latestay.booking.TestBookings;
import com.latestay.hotel.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The ledger rules (deposit 200, commission 100: the hotel is owed ₹100 = 10,000 paise per stay) and the weekly
 * settlement. The clock starts on Wednesday 11 Nov 2026, 22:30 IST; the job runs Monday 16 Nov 10:00 IST.
 */
class LedgerIT extends BookingItSupport {

    static final double LAT = 33.0;
    static final Instant MONDAY_10AM = Instant.parse("2026-11-16T04:30:00Z");

    @Autowired
    Ledger ledger;
    @Autowired
    Settlements settlements;

    BookingCreated confirmed(TestBookings.Agreed agreed) {
        BookingCreated created = fixtures.book(agreed);
        fixtures.pay(created);
        bookings.confirm(created.bookingId());
        return created;
    }

    BookingCreated checkedIn(TestBookings.Agreed agreed) {
        BookingCreated created = confirmed(agreed);
        bookings.checkInByOwner(created.bookingId());
        return created;
    }

    List<String> entries(UUID bookingId) {
        return ledger.forBooking(bookingId).stream().map(e -> e.type() + " " + e.amountPaise()).toList();
    }

    // Rules

    @Test
    void aCheckedInGuestEarnsTheHotelTheDepositMinusCommissionOnce() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = checkedIn(agreed);
        assertThat(entries(created.bookingId())).containsExactly("COMMISSION -10000", "DEPOSIT_COLLECTED 20000");

        // CHECKED_IN -> COMPLETED at the leave time: still owed once.
        clock.advance(Duration.ofHours(12));
        jobs.completeStays();
        assertThat(entries(created.bookingId())).hasSize(2);
        assertThat(ledger.balance(agreed.hotel().id())).isEqualTo(10_000);
    }

    @Test
    void anUnansweredArrivalCheckCompletesAndANoShowIsOwedToo() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated completed = confirmed(agreed);
        clock.set(Instant.parse("2026-11-11T20:00:00Z"));
        jobs.sendArrivalChecks();
        clock.set(Instant.parse("2026-11-12T05:00:00Z"));
        jobs.completeUnanswered();
        assertThat(entries(completed.bookingId())).hasSize(2);

        clock.set(Instant.parse("2026-11-11T17:00:00Z"));
        TestBookings.Agreed other = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated noShow = confirmed(other);
        bookings.noShowByOwner(noShow.bookingId());
        // Typing the code later checks them in: still owed once.
        bookings.checkInByOwner(noShow.bookingId());
        assertThat(entries(noShow.bookingId())).containsExactly("COMMISSION -10000", "DEPOSIT_COLLECTED 20000");
    }

    @Test
    void declinedTimedOutAndCancelledBookingsOweNothing() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 5);
        BookingCreated declined = fixtures.book(agreed);
        fixtures.pay(declined);
        bookings.decline(declined.bookingId());

        BookingCreated timedOut = fixtures.book(fixtures.agreedAnother(agreed));
        fixtures.pay(timedOut);
        clock.advance(Duration.ofMinutes(6));
        jobs.timeoutUnconfirmed();

        BookingCreated cancelled = fixtures.book(fixtures.agreedAnother(agreed));
        fixtures.pay(cancelled);
        bookings.cancel(cancelled.bookingId());
        jobs.issueRefunds();

        assertThat(entries(declined.bookingId())).isEmpty();
        assertThat(entries(timedOut.bookingId())).isEmpty();
        assertThat(entries(cancelled.bookingId())).isEmpty();
        assertThat(ledger.balance(agreed.hotel().id())).isZero();
    }

    @Test
    void rescueAfterTheGuestWasCountedTakesTheHotelsShareBack() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = confirmed(agreed);
        bookings.noShowByOwner(created.bookingId());
        bookings.travellerArrived(created.bookingId());
        bookings.rescue(created.bookingId());

        assertThat(entries(created.bookingId()))
                .containsExactlyInAnyOrder("DEPOSIT_COLLECTED 20000", "COMMISSION -10000", "REFUND -10000");
        assertThat(ledger.balance(agreed.hotel().id())).isZero();
    }

    @Test
    void hotelFailedBeforeTheStayOwesNothing() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = confirmed(agreed);
        bookings.rescue(created.bookingId());
        assertThat(entries(created.bookingId())).isEmpty();
    }

    @Test
    void theLedgerIsAppendOnly() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = checkedIn(agreed);
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entry SET amount_paise = 0 WHERE booking_id = :b")
                .param("b", created.bookingId()).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entry WHERE booking_id = :b")
                .param("b", created.bookingId()).update()).hasMessageContaining("append-only");
    }

    @Test
    void totalsAddUpAcrossBookingsAndAdjustments() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 5);
        checkedIn(agreed);
        mvc.perform(post("/api/admin/hotels/{id}/adjustments", agreed.hotel().id())
                        .with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\": -2500, \"note\": \"towel damage, agreed with owner\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("ADJUSTMENT"));
        assertThat(ledger.balance(agreed.hotel().id())).isEqualTo(10_000 - 2_500);
        mvc.perform(get("/api/admin/hotels/{id}/ledger", agreed.hotel().id()).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(jsonPath("$.balancePaise").value(7_500))
                .andExpect(jsonPath("$.entries.length()").value(3));
    }

    // Settlements

    @Test
    void mondaysJobSettlesLastWeekOnceAndPayingWritesAPayout() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 5);
        UUID hotel = agreed.hotel().id();
        checkedIn(agreed);
        checkedIn(fixtures.agreedAnother(agreed));

        // Before Monday the week isn't over.
        clock.set(Instant.parse("2026-11-15T18:00:00Z")); // Sunday 23:30 IST
        settlements.settleWeek();
        assertThat(settlements.forHotel(hotel, 10)).isEmpty();

        clock.set(MONDAY_10AM);
        settlements.settleWeek();
        settlements.settleWeek();
        assertThat(settlements.forHotel(hotel, 10)).singleElement().satisfies(s -> {
            assertThat(s.periodStart()).hasToString("2026-11-09");
            assertThat(s.periodEnd()).hasToString("2026-11-15");
            assertThat(s.amountPaise()).isEqualTo(20_000);
            assertThat(s.status()).isEqualTo("DUE");
        });
        Settlement s = settlements.forHotel(hotel, 10).getFirst();
        assertThat(settlements.lines(s.id())).hasSize(4);

        mvc.perform(get("/api/admin/settlements/{id}.csv", s.id()).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "night,booking_code,guest,type,amount_inr,note\r\n2026-11-11,")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(",A.V.,DEPOSIT_COLLECTED,200.00,")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(",,,TOTAL,200.00,")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Verma"))));

        mvc.perform(post("/api/admin/settlements/{id}/paid", s.id()).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"upiReference\": \"431234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlement.status").value("PAID"))
                .andExpect(jsonPath("$.settlement.upiReference").value("431234567890"));
        // Repeating with the same reference is fine; a different one is refused.
        settlements.markPaid(s.id(), "431234567890");
        assertThatThrownBy(() -> settlements.markPaid(s.id(), "999")).isInstanceOf(ConflictException.class);

        assertThat(ledger.forHotel(hotel)).filteredOn(e -> e.type() == LedgerEntryType.PAYOUT).singleElement()
                .satisfies(e -> assertThat(e.amountPaise()).isEqualTo(-20_000));
        assertThat(ledger.balance(hotel)).isZero();

        // Nothing new: next Monday makes no settlement for this hotel.
        clock.set(MONDAY_10AM.plus(Duration.ofDays(7)));
        settlements.settleWeek();
        assertThat(settlements.forHotel(hotel, 10)).hasSize(1);
    }

    @Test
    void aDisputedBookingAndANonPositiveWeekWaitForALaterSettlement() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 5);
        UUID hotel = agreed.hotel().id();
        BookingCreated disputed = confirmed(agreed);
        bookings.noShowByOwner(disputed.bookingId());
        bookings.travellerArrived(disputed.bookingId());

        clock.set(MONDAY_10AM);
        settlements.settleWeek();
        assertThat(settlements.forHotel(hotel, 10)).isEmpty();

        bookings.settle(disputed.bookingId(), DisputeOutcome.GUEST_STAYED);
        ledger.adjust(hotel, -15_000, "previous overpayment");
        clock.set(MONDAY_10AM.plus(Duration.ofDays(7)));
        settlements.settleWeek();
        // 10,000 owed - 15,000 adjustment: nothing to pay, so it waits.
        assertThat(settlements.forHotel(hotel, 10)).isEmpty();

        ledger.adjust(hotel, 8_000, "late check-in bonus");
        clock.set(MONDAY_10AM.plus(Duration.ofDays(14)));
        settlements.settleWeek();
        assertThat(settlements.forHotel(hotel, 10)).singleElement().satisfies(s -> {
            assertThat(s.amountPaise()).isEqualTo(3_000);
            assertThat(s.periodStart()).hasToString("2026-11-23");
        });
    }

    @Test
    void theNextPayoutDateIsTheComingMonday() {
        assertThat(settlements.nextPayoutDate()).hasToString("2026-11-16");
        clock.set(Instant.parse("2026-11-16T04:00:00Z")); // Monday 09:30 IST
        assertThat(settlements.nextPayoutDate()).hasToString("2026-11-16");
        clock.set(MONDAY_10AM);
        assertThat(settlements.nextPayoutDate()).hasToString("2026-11-23");
    }

    @Test
    void csvCellsCannotRunAsFormulas() {
        assertThat(Settlements.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(Settlements.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(Settlements.initials("asha  rani verma")).isEqualTo("A.R.V.");
        assertThat(Settlements.rupees(-2_550)).isEqualTo("-25.50");
    }
}
