package com.latestay.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.conversation.ConversationService;
import com.latestay.conversation.RoomGoneException;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.negotiation.SlotType;
import com.latestay.whatsapp.RecordingWhatsAppClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Room holds, the arrival check, disputes and Rescue. The clock starts at 22:30 IST on 11 Nov; the standard stay is
 * 23:30-09:00 (arrive_by 00:30 = 19:00Z).
 */
class SafetyIT extends BookingItSupport {

    static final double LAT = 31.0;
    static final Instant ARRIVE_AT = Instant.parse("2026-11-11T18:00:00Z");

    @Autowired
    ConversationService conversations;
    @Autowired
    RecordingWhatsAppClient whatsApp;

    BookingCreated confirmed(TestBookings.Agreed agreed) {
        BookingCreated created = fixtures.book(agreed);
        fixtures.pay(created);
        bookings.confirm(created.bookingId());
        return created;
    }

    List<Map<String, Object>> holds(UUID sessionId) {
        return jdbc.sql("SELECT status, booking_id FROM room_hold WHERE session_id = :s ORDER BY created_at")
                .param("s", sessionId).query().listOfRows();
    }

    // Room holds

    @Test
    void acceptingHoldsTheRoomForTenMinutesAndTheBookingTakesItOver() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        assertThat(roomsFree(agreed)).isEqualTo(1);
        assertThat(holds(agreed.sessionId())).singleElement().satisfies(h -> assertThat(h).containsEntry("status", "ACTIVE"));

        BookingCreated created = fixtures.book(agreed);
        assertThat(roomsFree(agreed)).isEqualTo(1);
        assertThat(holds(agreed.sessionId())).singleElement().satisfies(h -> assertThat(h)
                .containsEntry("status", "CONVERTED").containsEntry("booking_id", created.bookingId()));

        // A new report from the owner resets the count: bookings made before it are already left out.
        hotels.roomsFree(agreed.hotel(), TestBookings.NIGHT, 3);
        clock.advance(Duration.ofSeconds(1));
        assertThat(roomsFree(agreed)).isLessThanOrEqualTo(3);
    }

    @Test
    void anUnbookedHoldRunsOutAfterTenMinutes() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 1);
        assertThat(roomsFree(agreed)).isZero();

        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(roomsFree(agreed)).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(roomsFree(agreed)).isOne();
        jobs.expireHolds();
        assertThat(holds(agreed.sessionId())).singleElement().satisfies(h -> assertThat(h).containsEntry("status", "EXPIRED"));
    }

    @Test
    void cancelDeclineTimeoutAndPaymentExpiryGiveTheRoomBack() {
        TestBookings.Agreed cancelled = fixtures.agreed(LAT, 1400, 700, 1);
        BookingCreated c = fixtures.book(cancelled);
        fixtures.pay(c);
        bookings.cancel(c.bookingId());
        assertThat(roomsFree(cancelled)).isOne();
        assertThat(holds(cancelled.sessionId())).singleElement().satisfies(h -> assertThat(h).containsEntry("status", "RELEASED"));

        TestBookings.Agreed declined = fixtures.agreed(LAT, 1400, 700, 1);
        BookingCreated d = fixtures.book(declined);
        fixtures.pay(d);
        bookings.decline(d.bookingId());
        assertThat(roomsFree(declined)).isOne();

        TestBookings.Agreed timedOut = fixtures.agreed(LAT, 1400, 700, 1);
        BookingCreated t = fixtures.book(timedOut);
        fixtures.pay(t);
        TestBookings.Agreed unpaid = fixtures.agreed(LAT, 1400, 700, 1);
        BookingCreated u = fixtures.book(unpaid);
        clock.advance(Duration.ofMinutes(10));
        jobs.timeoutUnconfirmed();
        jobs.expireUnpaid();
        assertThat(statusOf(t.bookingId())).isEqualTo(BookingStatus.HOTEL_TIMEOUT);
        assertThat(statusOf(u.bookingId())).isEqualTo(BookingStatus.EXPIRED);
        assertThat(roomsFree(timedOut)).isOne();
        assertThat(roomsFree(unpaid)).isOne();
    }

    @Test
    void whenTheLastRoomGoesTheAcceptIsRefusedAndNothingIsAgreed() throws Exception {
        TestBookings.Opened mine = fixtures.opened(LAT, 1400, 700, 1, TestBookings.ARRIVE, TestBookings.LEAVE);
        UUID theirs = fixtures.openAnother(mine.hotel(), mine.room(), mine.stay());
        conversations.accept(theirs.toString());

        mvc.perform(post("/api/negotiations/{id}/accept", mine.sessionId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_GONE"))
                .andExpect(jsonPath("$.detail").value("Sorry, the last room at Booking Lodge just went."));
        mvc.perform(get("/api/negotiations/{id}", mine.sessionId()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.agreedPrice").doesNotExist());
        // Typing "ok done" is refused the same way.
        mvc.perform(post("/api/negotiations/{id}/messages", mine.sessionId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"ok done\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_GONE"));
    }

    @Test
    void twoTravellersAcceptingTheLastRoomAtOnceGetOneHold() throws Exception {
        for (int round = 0; round < 5; round++) {
            TestBookings.Opened first = fixtures.opened(LAT, 1400, 700, 1, TestBookings.ARRIVE, TestBookings.LEAVE);
            UUID second = fixtures.openAnother(first.hotel(), first.room(), first.stay());
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<String>> results = new ArrayList<>();
            for (UUID session : List.of(first.sessionId(), second)) {
                results.add(pool.submit((Callable<String>) () -> {
                    start.await();
                    try {
                        return conversations.accept(session.toString()).status().name();
                    } catch (RoomGoneException e) {
                        return "ROOM_GONE";
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get());
            }
            pool.shutdown();

            assertThat(outcomes).containsExactlyInAnyOrder("AGREED", "ROOM_GONE");
            assertThat(jdbc.sql("SELECT count(*) FROM room_hold WHERE hotel_id = :h AND status = 'ACTIVE'")
                    .param("h", first.hotel().id()).query(Integer.class).single()).isOne();
        }
    }

    // Arrival check

    @Test
    void theArrivalCheckWaitsAtLeastTwoHoursBeforeCompletingAnEarlyMorningStay() {
        // Arrive 08:30 on the 12th (night of the 11th): arrive_by 09:00, check at 10:00, so complete at 12:00, not 10:00.
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2, LocalDateTime.of(2026, 11, 12, 8, 30),
                LocalDateTime.of(2026, 11, 12, 13, 0));
        BookingCreated created = confirmed(agreed);

        clock.set(Instant.parse("2026-11-12T04:30:00Z")); // 10:00 IST
        jobs.sendArrivalChecks();
        jobs.completeUnanswered();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);
        clock.set(Instant.parse("2026-11-12T06:30:00Z").minusSeconds(1)); // 11:59:59
        jobs.completeUnanswered();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);
        clock.set(Instant.parse("2026-11-12T06:30:00Z"));
        jobs.completeUnanswered();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.COMPLETED);
    }

    // Disputes

    @Test
    void aNoShowCanBeDisputedOnceAndAnAdminSettlesIt() throws Exception {
        BookingCreated created = confirmed(fixtures.agreed(LAT, 1400, 700, 2));
        bookings.noShowByOwner(created.bookingId());
        mvc.perform(get("/api/bookings/{id}", created.bookingId()))
                .andExpect(jsonPath("$.status").value("NO_SHOW"))
                .andExpect(jsonPath("$.actions.didArrive").value(true));

        mvc.perform(post("/api/bookings/{id}/arrived", created.bookingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPUTED"))
                .andExpect(jsonPath("$.disputeReason").value("TRAVELLER_ARRIVED"));
        mvc.perform(post("/api/admin/bookings/{id}/settle", created.bookingId())
                        .with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"GUEST_DID_NOT_ARRIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("NO_SHOW"));

        // One dispute per booking.
        mvc.perform(get("/api/bookings/{id}", created.bookingId())).andExpect(jsonPath("$.actions.didArrive").value(false));
        mvc.perform(post("/api/bookings/{id}/arrived", created.bookingId())).andExpect(status().isConflict());
        assertThat(events(created.bookingId())).endsWith("HOTEL_CONFIRMED->NO_SHOW by HOTEL",
                "NO_SHOW->DISPUTED by TRAVELLER", "DISPUTED->NO_SHOW by ADMIN");
    }

    @Test
    void aGuestStayedSettlementCompletesTheBooking() {
        BookingCreated created = confirmed(fixtures.agreed(LAT, 1400, 700, 2));
        bookings.noShowByOwner(created.bookingId());
        bookings.travellerArrived(created.bookingId());
        bookings.settle(created.bookingId(), DisputeOutcome.GUEST_STAYED);
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.COMPLETED);
        assertThatThrownBy(() -> bookings.settle(created.bookingId(), DisputeOutcome.GUEST_STAYED))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void turnedAwayOnlyFromTheArrivalTimeThenTheHotelLeavesSearchAndTheAdminIsAlerted() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = confirmed(agreed);
        mvc.perform(get("/api/bookings/{id}", created.bookingId())).andExpect(jsonPath("$.actions.turnedAway").value(false));
        mvc.perform(post("/api/bookings/{id}/turned-away", created.bookingId())).andExpect(status().isBadRequest());

        clock.set(ARRIVE_AT);
        mvc.perform(get("/api/bookings/{id}", created.bookingId())).andExpect(jsonPath("$.actions.turnedAway").value(true));
        mvc.perform(post("/api/bookings/{id}/turned-away", created.bookingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPUTED"))
                .andExpect(jsonPath("$.disputeReason").value("TURNED_AWAY"));

        assertThat(roomsFree(agreed)).isZero();
        assertThat(holds(agreed.sessionId())).singleElement().satisfies(h -> assertThat(h).containsEntry("status", "RELEASED"));
        String search = mvc.perform(get("/api/search").param("lat", String.valueOf(agreed.hotel().lat()))
                        .param("lng", String.valueOf(agreed.hotel().lng())).param("arrive", "2026-11-12T01:00")
                        .param("leave", "2026-11-12T09:00").param("guests", "2"))
                .andReturn().getResponse().getContentAsString();
        assertThat(search).doesNotContain(agreed.hotel().slug());

        Map<String, Object> alert = jdbc.sql("""
                        SELECT wa_id, template, payload::text AS payload FROM whatsapp_message
                        WHERE booking_id = :b AND template = 'admin_alert'""")
                .param("b", created.bookingId()).query().singleRow();
        assertThat(alert).containsEntry("wa_id", "919000011111");
        assertThat((String) alert.get("payload")).contains("guest turned away at the desk").contains("Booking Lodge");
    }

    @Test
    void aTurnedAwayHotelComesBackAfterTheHidePeriodUnlessKeptHidden() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = confirmed(agreed);
        clock.set(ARRIVE_AT);
        bookings.turnedAway(created.bookingId());
        assertThat(roomsFree(agreed)).isZero();

        clock.advance(Duration.ofHours(6).minusMinutes(1));
        assertThat(roomsFree(agreed)).isZero();
        clock.advance(Duration.ofMinutes(2));
        assertThat(roomsFree(agreed)).isPositive();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.DISPUTED);

        // Settling still works after the hotel is visible again.
        bookings.settle(created.bookingId(), DisputeOutcome.GUEST_STAYED);
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void keepHiddenHoldsTheHotelBackUntilTheDisputeIsSettled() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = confirmed(agreed);
        clock.set(ARRIVE_AT);
        bookings.turnedAway(created.bookingId());

        mvc.perform(post("/api/admin/bookings/{id}/keep-hidden", created.bookingId())
                        .with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.keepHiddenAt").isNotEmpty())
                .andExpect(jsonPath("$.booking.hideUntil").value("2026-11-12T00:00:00Z"));
        clock.advance(Duration.ofHours(12));
        assertThat(roomsFree(agreed)).isZero();

        bookings.settle(created.bookingId(), DisputeOutcome.GUEST_STAYED);
        assertThat(roomsFree(agreed)).isPositive();
    }

    @Test
    void onlyAnOpenTurnedAwayDisputeCanBeKeptHidden() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = confirmed(agreed);
        assertThatThrownBy(() -> bookings.keepHidden(created.bookingId()))
                .isInstanceOf(com.latestay.hotel.ConflictException.class);
    }

    // Rescue

    @Test
    void rescueRefundsAtOnceCountsAgainstTheHotelAndOffersThreeHotelsWithRoomsNow() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        for (int i = 1; i <= 4; i++) {
            Hotel nearby = hotels.hotel("Nearby " + i, agreed.hotel().lat() + i * 0.005, agreed.hotel().lng(),
                    List.of(), HotelPolicies.NONE, true);
            hotels.room(nearby, 1200, 2, 600, SlotType.LATE_NIGHT);
            hotels.roomsFree(nearby, TestBookings.NIGHT, 2);
        }
        BookingCreated created = confirmed(agreed);
        clock.set(Instant.parse("2026-11-11T19:10:00Z")); // 00:40 IST
        bookings.turnedAway(created.bookingId());

        mvc.perform(post("/api/admin/bookings/{id}/rescue", created.bookingId()).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("HOTEL_FAILED"))
                .andExpect(jsonPath("$.booking.hotelFailures").value(1));

        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isOne();
        assertThat(payment(created.bookingId())).containsEntry("status", "REFUNDED");
        mvc.perform(get("/api/bookings/{id}", created.bookingId()))
                .andExpect(jsonPath("$.status").value("HOTEL_FAILED"))
                .andExpect(jsonPath("$.refund.status").value("PROCESSED"))
                .andExpect(jsonPath("$.alternatives.length()").value(3))
                .andExpect(jsonPath("$.alternatives[0].name").value("Nearby 1"));
        String rescueArrival = jdbc.sql("""
                        SELECT to_char(q.arrive_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI') FROM booking b
                        JOIN stay_request q ON q.id = b.rescue_stay_request_id WHERE b.id = :id""")
                .param("id", created.bookingId()).query(String.class).single();
        assertThat(rescueArrival).isEqualTo("00:45");
    }

    @Test
    void rescueIsRefusedBeforePaymentAndAfterARefund() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        assertThatThrownBy(() -> bookings.rescue(created.bookingId())).isInstanceOf(IllegalTransitionException.class);
        fixtures.pay(created);
        bookings.decline(created.bookingId());
        jobs.issueRefunds();
        assertThatThrownBy(() -> bookings.rescue(created.bookingId())).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void thePassOffersCancelOnlyBeforeTheHotelConfirms() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        mvc.perform(get("/api/bookings/{id}", created.bookingId())).andExpect(jsonPath("$.actions.cancel").value(true));
        fixtures.pay(created);
        mvc.perform(get("/api/bookings/{id}", created.bookingId())).andExpect(jsonPath("$.actions.cancel").value(true));
        bookings.confirm(created.bookingId());
        mvc.perform(get("/api/bookings/{id}", created.bookingId())).andExpect(jsonPath("$.actions.cancel").value(false));
        mvc.perform(post("/api/bookings/{id}/cancel", created.bookingId())).andExpect(status().isConflict());
    }
}
