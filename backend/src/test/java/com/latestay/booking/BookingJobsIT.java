package com.latestay.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.payment.FakeRazorpayGateway;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The timeout jobs with a hand-moved clock. The stay is 23:30-09:00 IST on the night of 11 Nov; the clock starts
 * at 22:30 IST (17:00Z). arrive_by is 00:30 IST (19:00Z), leave_at 09:00 IST (03:30Z on the 12th).
 */
class BookingJobsIT extends BookingItSupport {

    static final double LAT = 27.0;
    static final Duration SECOND = Duration.ofSeconds(1);

    BookingCreated booked() {
        return fixtures.book(fixtures.agreed(LAT, 1400, 700, 2));
    }

    @Test
    void unpaidBookingExpiresExactlyTenMinutesAfterCreation() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        assertThat(roomsFree(agreed)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(10).minus(SECOND));
        jobs.expireUnpaid();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.PENDING_PAYMENT);

        clock.advance(SECOND);
        assertThat(jobs.expireUnpaid()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.EXPIRED);
        assertThat(events(created.bookingId())).last().isEqualTo("PENDING_PAYMENT->EXPIRED by JOB");
        assertThat(roomsFree(agreed)).isEqualTo(2);

        // Running again changes nothing.
        jobs.expireUnpaid();
        assertThat(events(created.bookingId())).hasSize(2);
    }

    @Test
    void paidBookingTimesOutFiveMinutesAfterPaymentAndIsRefunded() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        clock.advance(Duration.ofMinutes(2));
        fixtures.pay(created);

        clock.advance(Duration.ofMinutes(5).minus(SECOND));
        jobs.timeoutUnconfirmed();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.DEPOSIT_PAID);

        clock.advance(SECOND);
        jobs.timeoutUnconfirmed();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_TIMEOUT);
        assertThat(payment(created.bookingId())).containsEntry("status", "REFUND_PENDING");
        assertThat(roomsFree(agreed)).isEqualTo(2);

        jobs.issueRefunds();
        jobs.issueRefunds();
        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isOne();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.REFUNDED);
        assertThat(events(created.bookingId())).endsWith("DEPOSIT_PAID->HOTEL_TIMEOUT by JOB",
                "HOTEL_TIMEOUT->REFUNDED by RAZORPAY");
    }

    @Test
    void confirmationBeforeTheDeadlineStopsTheTimeout() {
        BookingCreated created = booked();
        fixtures.pay(created);
        clock.advance(Duration.ofMinutes(4));
        bookings.confirm(created.bookingId());
        clock.advance(Duration.ofMinutes(10));
        jobs.timeoutUnconfirmed();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);
    }

    /**
     * Replaces the old automatic NO_SHOW: an hour after arrive_by (00:30 IST) the owner is asked; nobody answers, so
     * at 10:00 the next morning the booking completes and the hotel keeps the deposit.
     */
    @Test
    void anUnansweredArrivalCheckCompletesTheBookingAtTenTheNextMorning() {
        BookingCreated created = booked();
        fixtures.pay(created);
        bookings.confirm(created.bookingId());

        clock.set(Instant.parse("2026-11-11T20:00:00Z").minus(SECOND)); // 01:29:59 IST
        jobs.sendArrivalChecks();
        assertThat(arrivalCheckSent(created)).isFalse();

        clock.set(Instant.parse("2026-11-11T20:00:00Z")); // 01:30 IST = arrive_by + 60 min
        jobs.sendArrivalChecks();
        jobs.sendArrivalChecks();
        assertThat(arrivalCheckSent(created)).isTrue();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);

        clock.set(Instant.parse("2026-11-12T04:30:00Z").minus(SECOND)); // 09:59:59 IST
        jobs.completeUnanswered();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);

        clock.set(Instant.parse("2026-11-12T04:30:00Z")); // 10:00 IST on the 12th
        jobs.completeUnanswered();
        jobs.issueRefunds();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.COMPLETED);
        assertThat(events(created.bookingId())).last().isEqualTo("HOTEL_CONFIRMED->COMPLETED by JOB");
        assertThat(payment(created.bookingId())).containsEntry("status", "CAPTURED");
        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isZero();
    }

    boolean arrivalCheckSent(BookingCreated created) {
        return jdbc.sql("SELECT arrival_check_sent_at IS NOT NULL FROM booking WHERE id = :id")
                .param("id", created.bookingId()).query(Boolean.class).single();
    }

    @Test
    void checkedInStayCompletesAtLeaveTime() {
        BookingCreated created = booked();
        fixtures.pay(created);
        bookings.confirm(created.bookingId());
        String code = jdbc.sql("SELECT code FROM booking WHERE id = :id").param("id", created.bookingId())
                .query(String.class).single();
        clock.set(Instant.parse("2026-11-11T18:10:00Z"));
        bookings.checkIn(created.bookingId(), code);

        // Checked-in guests get no arrival check and don't complete before they leave.
        clock.set(Instant.parse("2026-11-12T03:30:00Z").minus(SECOND));
        jobs.sendArrivalChecks();
        jobs.completeUnanswered();
        jobs.completeStays();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.CHECKED_IN);

        clock.set(Instant.parse("2026-11-12T03:30:00Z"));
        jobs.completeStays();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.COMPLETED);
        assertThat(events(created.bookingId())).last().isEqualTo("CHECKED_IN->COMPLETED by JOB");
    }

    @Test
    void aFailedRefundIsRetriedOnTheNextRun() {
        BookingCreated created = booked();
        fixtures.pay(created);
        bookings.decline(created.bookingId());
        gateway.failNextRefunds.set(1);

        assertThat(jobs.issueRefunds()).isZero();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_DECLINED);
        assertThat(payment(created.bookingId())).containsEntry("status", "REFUND_PENDING")
                .containsEntry("refund_attempts", 1)
                .hasEntrySatisfying("last_error", e -> assertThat((String) e).contains("502"));

        clock.advance(Duration.ofSeconds(15));
        jobs.issueRefunds();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.REFUNDED);
        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isOne();
        assertThat(payment(created.bookingId())).containsEntry("status", "REFUNDED")
                .containsEntry("refund_attempts", 2);
    }

    @Test
    void aRefundRazorpayHasNotProcessedYetWaitsForTheWebhook() {
        BookingCreated created = booked();
        fixtures.pay(created);
        bookings.decline(created.bookingId());
        gateway.refundsProcessImmediately = false;

        jobs.issueRefunds();
        assertThat(payment(created.bookingId())).containsEntry("status", "REFUND_REQUESTED");
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_DECLINED);
        jobs.issueRefunds();
        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isOne();
    }

    @Test
    void aDepositThatLandsAfterExpiryIsRefundedAndTheBookingStaysExpired() {
        BookingCreated created = booked();
        clock.advance(Duration.ofMinutes(10));
        jobs.expireUnpaid();

        String paymentId = "pay_late_" + created.bookingId().toString().substring(0, 8);
        bookings.verify(created.bookingId(), created.checkout().orderId(), paymentId,
                FakeRazorpayGateway.sign(created.checkout().orderId(), paymentId));
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.EXPIRED);
        assertThat(payment(created.bookingId())).containsEntry("status", "REFUND_PENDING");

        jobs.issueRefunds();
        assertThat(gateway.refundCalls(paymentId)).isOne();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.EXPIRED);
        assertThat(payment(created.bookingId())).containsEntry("status", "REFUNDED");
    }

    @Test
    void jobsOnlyMoveBookingsInTheirOwnState() {
        BookingCreated pending = booked();
        BookingCreated paid = booked();
        fixtures.pay(paid);

        clock.set(Instant.parse("2026-11-12T12:00:00Z"));
        jobs.timeoutUnconfirmed();
        jobs.sendArrivalChecks();
        jobs.completeUnanswered();
        jobs.completeStays();
        assertThat(statusOf(pending.bookingId())).isEqualTo(BookingStatus.PENDING_PAYMENT);
        jobs.expireUnpaid();
        assertThat(statusOf(pending.bookingId())).isEqualTo(BookingStatus.EXPIRED);
        assertThat(statusOf(paid.bookingId())).isEqualTo(BookingStatus.HOTEL_TIMEOUT);
    }

    @Test
    void phoneNumbersAreDeletedNinetyDaysAfterTheStay() throws Exception {
        BookingCreated created = booked();
        fixtures.pay(created);
        jdbc.sql("UPDATE payment SET raw_event = CAST(:raw AS jsonb) WHERE booking_id = :id")
                .param("raw", "{\"payload\":{\"payment\":{\"entity\":{\"contact\":\"+919876543210\"}}}}")
                .param("id", created.bookingId()).update();
        Instant leaveAt = Instant.parse("2026-11-12T03:30:00Z");

        clock.set(leaveAt.plus(Duration.ofDays(90)).minus(SECOND));
        jobs.deleteOldPhoneNumbers();
        assertThat(phone(created)).isEqualTo("+919876543210");

        clock.set(leaveAt.plus(Duration.ofDays(90)));
        jobs.deleteOldPhoneNumbers();
        assertThat(phone(created)).isNull();
        assertThat(payment(created.bookingId()).get("raw")).isNull();
        assertThat(jdbc.sql("SELECT phone_deleted_at FROM booking WHERE id = :id").param("id", created.bookingId())
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(leaveAt.plus(Duration.ofDays(90)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/bookings/{id}", created.bookingId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.phone")
                        .doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.guestName")
                        .value("Asha Verma"));
    }

    String phone(BookingCreated created) {
        return jdbc.sql("SELECT phone FROM booking WHERE id = :id").param("id", created.bookingId())
                .query(String.class).optional().orElse(null);
    }
}
