package com.latestay.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.Booking;
import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.BookingRepository;
import com.latestay.booking.TestBookings;
import com.latestay.review.ReviewInvites;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Device registration, one notification per status change, cleanup, and what a payload may carry. */
class PushIT extends BookingItSupport {

    static final double LAT = 43.0;
    static final AtomicInteger NEXT = new AtomicInteger();

    @Autowired
    RecordingPushGateway push;
    @Autowired
    PushSender sender;
    @Autowired
    PushOutbox outbox;
    @Autowired
    Devices devices;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    ReviewInvites reviewInvites;

    @BeforeEach
    void resetPush() {
        push.reset();
    }

    static String token() {
        return "fcm-token-" + UUID.randomUUID() + "-" + NEXT.incrementAndGet();
    }

    BookingCreated paid() {
        BookingCreated created = fixtures.book(fixtures.agreed(LAT, 1400, 700, 4));
        fixtures.pay(created);
        return created;
    }

    void register(String token, UUID bookingId) throws Exception {
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token": "%s", "platform": "ANDROID", "bookingId": "%s"}""".formatted(token, bookingId)))
                .andExpect(status().isNoContent());
    }

    List<String> statuses(UUID bookingId) {
        return jdbc.sql("SELECT kind || ' ' || status FROM push_message WHERE booking_id = :b ORDER BY id")
                .param("b", bookingId).query(String.class).list();
    }

    @Test
    void registeringATokenIsIdempotentAndChecked() throws Exception {
        BookingCreated created = paid();
        String token = token();
        register(token, created.bookingId());
        clock.advance(Duration.ofMinutes(5));
        register(token, created.bookingId());
        assertThat(jdbc.sql("SELECT count(*) FROM device_token WHERE token = :t").param("t", token)
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT last_seen_at > created_at FROM device_token WHERE token = :t").param("t", token)
                .query(Boolean.class).single()).isTrue();
        assertThat(devices.tokensFor(created.bookingId())).containsExactly(token);

        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token": "%s", "platform": "IOS", "bookingId": "%s"}""".formatted(token(), UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token": "short", "platform": "IOS"}""")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token": "%s", "platform": "WINDOWS"}""".formatted(token()))).andExpect(status().isBadRequest());
    }

    @Test
    void confirmingSendsOneNotificationThatOpensThePass() throws Exception {
        BookingCreated created = paid();
        String token = token();
        register(token, created.bookingId());
        bookings.confirm(created.bookingId());

        assertThat(statuses(created.bookingId())).containsExactly("HOTEL_CONFIRMED QUEUED");
        assertThat(sender.sendQueued()).isPositive();
        assertThat(sender.sendQueued()).isZero();
        assertThat(push.to(token)).singleElement().satisfies(s -> {
            assertThat(s.payload().body()).isEqualTo("Booking Lodge confirmed. Your code is ready.");
            assertThat(s.payload().data()).containsEntry("path", "/pass/" + created.bookingId())
                    .containsEntry("bookingId", created.bookingId().toString());
        });
        assertThat(statuses(created.bookingId())).containsExactly("HOTEL_CONFIRMED SENT");
        // A replayed change can't queue it again.
        assertThat(outbox.queue(created.bookingId(), "HOTEL_CONFIRMED", "again", "/pass/" + created.bookingId()))
                .isFalse();
    }

    @Test
    void nothingIsQueuedWithoutADevice() {
        BookingCreated created = paid();
        bookings.confirm(created.bookingId());
        assertThat(statuses(created.bookingId())).isEmpty();
    }

    @Test
    void aDeviceRegisteredWithAVerifiedNumberHearsAboutLaterBookingsFromIt() throws Exception {
        // A number of its own: the fixtures' shared number would match every other test's bookings.
        String mobile = "8" + String.format("%09d", (System.nanoTime() + NEXT.incrementAndGet()) % 1_000_000_000L);
        BookingCreated first = fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 4), mobile, null);
        fixtures.pay(first);
        String token = token();
        mvc.perform(post("/api/devices").cookie(fixtures.phoneCookie("+91" + mobile))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"token": "%s", "platform": "IOS", "bookingId": "%s"}"""
                                .formatted(token, first.bookingId())))
                .andExpect(status().isNoContent());
        BookingCreated later = fixtures.bookWithPromo(fixtures.agreed(LAT, 1400, 700, 4), mobile, null);
        assertThat(devices.tokensFor(later.bookingId())).containsExactly(token);
        assertThat(devices.tokensFor(paid().bookingId())).doesNotContain(token);
    }

    @Test
    void goneTokensAreDeletedAndFailuresRetried() throws Exception {
        BookingCreated created = paid();
        String gone = token();
        String flaky = token();
        register(gone, created.bookingId());
        register(flaky, created.bookingId());
        push.gone.add(gone);
        push.failing.add(flaky);
        bookings.confirm(created.bookingId());

        sender.sendQueued();
        assertThat(devices.tokensFor(created.bookingId())).containsExactly(flaky);
        assertThat(statuses(created.bookingId())).containsExactly("HOTEL_CONFIRMED QUEUED");
        push.failing.clear();
        sender.sendQueued();
        assertThat(push.to(flaky)).hasSize(1);
        assertThat(statuses(created.bookingId())).containsExactly("HOTEL_CONFIRMED SENT");
    }

    @Test
    void withoutFirebaseMessagesAreMarkedNotSent() throws Exception {
        BookingCreated created = paid();
        register(token(), created.bookingId());
        push.configured = false;
        bookings.decline(created.bookingId());
        sender.sendQueued();
        assertThat(statuses(created.bookingId())).containsExactly("HOTEL_DECLINED NOT_SENT");
        assertThat(push.sent).isEmpty();
    }

    @Test
    void tokensNotSeenFor90DaysAreDeleted() throws Exception {
        BookingCreated created = paid();
        String old = token();
        register(old, created.bookingId());
        clock.advance(Duration.ofDays(91));
        String fresh = token();
        register(fresh, created.bookingId());
        sender.cleanup();
        assertThat(devices.tokensFor(created.bookingId())).containsExactly(fresh);
    }

    @Test
    void noPayloadCarriesACodePhoneNumberOrName() throws Exception {
        // Every kind: confirmed, declined, timed out, cancelled after paying, rescued, refunded, review request.
        BookingCreated confirmed = paid();
        BookingCreated declined = paid();
        BookingCreated timedOut = paid();
        BookingCreated cancelled = paid();
        BookingCreated rescued = paid();
        BookingCreated stayed = paid();
        List<BookingCreated> all = List.of(confirmed, declined, timedOut, cancelled, rescued, stayed);
        for (BookingCreated b : all) {
            register(token(), b.bookingId());
        }
        bookings.confirm(confirmed.bookingId());
        bookings.decline(declined.bookingId());
        clock.advance(Duration.ofMinutes(6));
        bookings.timeoutIfUnconfirmed(timedOut.bookingId());
        bookings.cancel(cancelled.bookingId());
        bookings.confirm(rescued.bookingId());
        bookings.rescue(rescued.bookingId());
        jobs.issueRefunds();
        bookings.confirm(stayed.bookingId());
        bookings.checkInByOwner(stayed.bookingId());
        clock.set(Instant.parse("2026-11-12T05:30:00Z"));
        bookings.completeIfLeft(stayed.bookingId());
        reviewInvites.send();
        while (sender.sendQueued() > 0) {
            // drain
        }

        assertThat(push.sent.stream().map(s -> s.payload().body()).toList()).anyMatch(t -> t.contains("confirmed"))
                .anyMatch(t -> t.contains("couldn't take")).anyMatch(t -> t.contains("didn't confirm in time"))
                .anyMatch(t -> t.contains("is cancelled")).anyMatch(t -> t.contains("let you down"))
                .anyMatch(t -> t.contains("refund has been processed")).anyMatch(t -> t.startsWith("How was"));
        for (BookingCreated b : all) {
            Booking booking = bookingRepository.find(b.bookingId()).orElseThrow();
            for (RecordingPushGateway.Sent s : push.sent) {
                String everything = s.payload().title() + " " + s.payload().body() + " " + s.payload().data();
                assertThat(everything).doesNotContain(booking.code(), "Asha", "Verma", "98765", "43210", "+91");
                assertThat(s.payload().body()).doesNotContainPattern("[0-9]{4}");
                assertThat(s.payload().data().keySet()).containsExactlyInAnyOrder("bookingId", "path");
            }
        }
    }
}
