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

    @Test
    void confirmedGuestWhoNeverArrivesIsANoShowAnHourAfterArriveByAndKeepsNoDeposit() {
        BookingCreated created = booked();
        fixtures.pay(created);
        bookings.confirm(created.bookingId());

        clock.set(Instant.parse("2026-11-11T20:00:00Z").minus(SECOND)); // 01:29:59 IST
        jobs.markNoShows();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);

        clock.set(Instant.parse("2026-11-11T20:00:00Z")); // 01:30 IST = arrive_by + 60 min
        jobs.markNoShows();
        jobs.issueRefunds();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(payment(created.bookingId())).containsEntry("status", "CAPTURED");
        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isZero();
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

        // Checked-in guests are never no-shows.
        clock.set(Instant.parse("2026-11-12T03:30:00Z").minus(SECOND));
        jobs.markNoShows();
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
        jobs.markNoShows();
        jobs.completeStays();
        assertThat(statusOf(pending.bookingId())).isEqualTo(BookingStatus.PENDING_PAYMENT);
        jobs.expireUnpaid();
        assertThat(statusOf(pending.bookingId())).isEqualTo(BookingStatus.EXPIRED);
        assertThat(statusOf(paid.bookingId())).isEqualTo(BookingStatus.HOTEL_TIMEOUT);
    }
}
