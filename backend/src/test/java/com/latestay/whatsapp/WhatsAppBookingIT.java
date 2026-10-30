package com.latestay.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.TestBookings;
import com.latestay.whatsapp.OwnerReply.Action;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** booking_request goes out on DEPOSIT_PAID; the owner's buttons move the booking; the 5-minute timeout holds. */
class WhatsAppBookingIT extends WhatsAppItSupport {

    static final double LAT = 30.0;

    record Paid(TestBookings.Agreed agreed, BookingCreated created) {
    }

    Paid paid() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        fixtures.pay(created);
        return new Paid(agreed, created);
    }

    JsonNode bookingRequest(Paid p) {
        return rows(p.agreed().hotel().id()).stream().filter(r -> "booking_request".equals(r.get("template")))
                .map(r -> read(r.get("payload"))).findFirst().orElseThrow();
    }

    void owner(Paid p, Action action) throws Exception {
        tap(p.agreed().hotel(), OwnerReplyParser.bookingPayload(p.created().bookingId(), action),
                action == Action.CONFIRM ? "Confirm" : "Can't take it");
    }

    String code(Paid p) {
        return jdbc.sql("SELECT code FROM booking WHERE id = :id").param("id", p.created().bookingId())
                .query(String.class).single();
    }

    @Test
    void depositPaidQueuesBookingRequestWithTheAmountsCodeAndButtons() {
        Paid p = paid();
        JsonNode message = bookingRequest(p);

        assertThat(message.path("to").asText()).isEqualTo(waId(p.agreed().hotel()));
        JsonNode components = message.path("template").path("components");
        List<String> values = new java.util.ArrayList<>();
        components.get(0).path("parameters").forEach(v -> values.add(v.path("text").asText()));
        assertThat(values).containsExactly("2", "11:30 pm", "800", "200", code(p));
        assertThat(components.get(1).path("parameters").get(0).path("payload").asText())
                .isEqualTo(OwnerReplyParser.bookingPayload(p.created().bookingId(), Action.CONFIRM));
        assertThat(components.get(2).path("parameters").get(0).path("payload").asText())
                .isEqualTo(OwnerReplyParser.bookingPayload(p.created().bookingId(), Action.DECLINE));

        // Paying twice (Checkout handler and webhook) queues one request.
        fixtures.pay(p.created());
        assertThat(rows(p.agreed().hotel().id()).stream().filter(r -> "booking_request".equals(r.get("template"))))
                .hasSize(1);
    }

    @Test
    void confirmMovesTheBookingToHotelConfirmed() throws Exception {
        Paid p = paid();
        owner(p, Action.CONFIRM);

        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);
        assertThat(events(p.created().bookingId())).last().isEqualTo("DEPOSIT_PAID->HOTEL_CONFIRMED by HOTEL");
        assertThat(outboundTexts(p.agreed().hotel().id())).last().asString()
                .isEqualTo("Confirmed. 2 guests arriving around 11:30 pm. They'll show code " + code(p)
                        + " at the desk; collect ₹800.");
    }

    @Test
    void cantTakeItDeclinesAndRefunds() throws Exception {
        Paid p = paid();
        owner(p, Action.DECLINE);

        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.HOTEL_DECLINED);
        assertThat(payment(p.created().bookingId())).containsEntry("status", "REFUND_PENDING");
        jobs.issueRefunds();
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.REFUNDED);
        assertThat(roomsFree(p.agreed())).isEqualTo(2);
    }

    @Test
    void tappingTwiceChangesTheBookingOnce() throws Exception {
        Paid p = paid();
        owner(p, Action.CONFIRM);
        owner(p, Action.CONFIRM);
        owner(p, Action.DECLINE);

        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);
        assertThat(events(p.created().bookingId())).filteredOn(e -> e.contains("HOTEL_CONFIRMED")).hasSize(1);
        List<String> replies = outboundTexts(p.agreed().hotel().id());
        assertThat(replies.get(replies.size() - 2)).isEqualTo("Already confirmed. The guest will show code "
                + code(p) + ".");
        assertThat(replies.getLast()).startsWith("This booking is already confirmed.");
    }

    @Test
    void theFiveMinuteTimeoutStillAppliesAndALateTapChangesNothing() throws Exception {
        Paid p = paid();
        clock.advance(Duration.ofMinutes(5));
        jobs.timeoutUnconfirmed();
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.HOTEL_TIMEOUT);

        owner(p, Action.CONFIRM);
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.HOTEL_TIMEOUT);
        assertThat(outboundTexts(p.agreed().hotel().id())).last().asString()
                .startsWith("This booking timed out after 5 minutes");

        // The owner was told with booking_cancelled when it timed out.
        JsonNode cancelled = rows(p.agreed().hotel().id()).stream()
                .filter(r -> "booking_cancelled".equals(r.get("template"))).map(r -> read(r.get("payload")))
                .findFirst().orElseThrow();
        JsonNode values = cancelled.path("template").path("components").get(0).path("parameters");
        assertThat(values.get(0).path("text").asText()).isEqualTo(code(p));
        assertThat(values.get(1).path("text").asText()).isEqualTo("it wasn't confirmed within 5 minutes");
    }

    @Test
    void aTravellerCancellationIsSentToTheOwner() throws Exception {
        Paid p = paid();
        bookings.cancel(p.created().bookingId());
        Map<String, Object> notice = rows(p.agreed().hotel().id()).stream()
                .filter(r -> "booking_cancelled".equals(r.get("template"))).findFirst().orElseThrow();
        assertThat(read(notice.get("payload")).path("template").path("components").get(0).path("parameters").get(1)
                .path("text").asText()).isEqualTo("the guest cancelled");

        owner(p, Action.CONFIRM);
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(outboundTexts(p.agreed().hotel().id())).last().asString()
                .startsWith("The guest cancelled this booking.");
    }

    @Test
    void anotherHotelsOwnerCannotAnswerTheBooking() throws Exception {
        Paid mine = paid();
        Paid theirs = paid();
        tap(mine.agreed().hotel(), OwnerReplyParser.bookingPayload(theirs.created().bookingId(), Action.CONFIRM),
                "Confirm");
        assertThat(statusOf(theirs.created().bookingId())).isEqualTo(BookingStatus.DEPOSIT_PAID);
    }

    @Test
    void anUnpaidBookingSendsNothing() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        fixtures.book(agreed);
        assertThat(rows(agreed.hotel().id())).isEmpty();
    }

    // Arrival check

    Paid confirmed() throws Exception {
        Paid p = paid();
        owner(p, Action.CONFIRM);
        return p;
    }

    @Test
    void theArrivalCheckAsksTheOwnerAnHourAfterArriveBy() throws Exception {
        Paid p = confirmed();
        clock.set(java.time.Instant.parse("2026-11-11T20:00:00Z"));
        jobs.sendArrivalChecks();
        JsonNode check = rows(p.agreed().hotel().id()).stream().filter(r -> "arrival_check".equals(r.get("template")))
                .map(r -> read(r.get("payload"))).findFirst().orElseThrow();
        JsonNode components = check.path("template").path("components");
        assertThat(components.get(0).path("parameters").get(0).path("text").asText()).isEqualTo("Asha Verma");
        assertThat(components.get(0).path("parameters").get(1).path("text").asText()).isEqualTo(code(p));
        assertThat(components.get(1).path("parameters").get(0).path("payload").asText())
                .isEqualTo(OwnerReplyParser.arrivalPayload(p.created().bookingId(), true));
        assertThat(components.get(2).path("parameters").get(0).path("payload").asText())
                .isEqualTo(OwnerReplyParser.arrivalPayload(p.created().bookingId(), false));
    }

    @Test
    void arrivedChecksTheGuestIn() throws Exception {
        Paid p = confirmed();
        tap(p.agreed().hotel(), OwnerReplyParser.arrivalPayload(p.created().bookingId(), true), "Arrived");
        tap(p.agreed().hotel(), OwnerReplyParser.arrivalPayload(p.created().bookingId(), true), "Arrived");
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(events(p.created().bookingId())).filteredOn(e -> e.endsWith("CHECKED_IN by HOTEL")).hasSize(1);
        assertThat(outboundTexts(p.agreed().hotel().id())).last().asString().isEqualTo("Already noted, thanks.");
    }

    @Test
    void didntArriveIsANoShowAndTheCodeLaterStillChecksTheGuestIn() throws Exception {
        Paid p = confirmed();
        tap(p.agreed().hotel(), OwnerReplyParser.arrivalPayload(p.created().bookingId(), false), "Didn't arrive");
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(outboundTexts(p.agreed().hotel().id())).last().asString().contains("keep the ₹200 deposit");

        say(p.agreed().hotel(), "code " + code(p));
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(outboundTexts(p.agreed().hotel().id())).last().asString()
                .isEqualTo("Code " + code(p) + " is right: Asha Verma, 2 guests. Collect ₹800. Checked in.");
    }

    @Test
    void typingTheCodeChecksInOnlyThisOwnersGuest() throws Exception {
        Paid mine = confirmed();
        Paid theirs = confirmed();
        say(mine.agreed().hotel(), code(theirs));
        assertThat(statusOf(theirs.created().bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);
        // Another hotel's code is not a desk code for this owner: just the help reply.
        assertThat(outboundTexts(mine.agreed().hotel().id())).last().asString().isEqualTo(OwnerMessageHandler.HELP);
        say(mine.agreed().hotel(), code(mine));
        assertThat(statusOf(mine.created().bookingId())).isEqualTo(BookingStatus.CHECKED_IN);
    }

    @Test
    void aCodeOfASettledBookingIsNotADeskCode() throws Exception {
        Paid p = confirmed();
        say(p.agreed().hotel(), code(p));
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.CHECKED_IN);
        // Checked in already, so no longer active: the same digits now get the help reply.
        say(p.agreed().hotel(), code(p));
        assertThat(outboundTexts(p.agreed().hotel().id())).last().asString().isEqualTo(OwnerMessageHandler.HELP);
    }

    @Test
    void anArrivalAnswerAfterTheBookingWasSettledChangesNothing() throws Exception {
        Paid p = confirmed();
        say(p.agreed().hotel(), code(p));
        tap(p.agreed().hotel(), OwnerReplyParser.arrivalPayload(p.created().bookingId(), false), "Didn't arrive");
        assertThat(statusOf(p.created().bookingId())).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(outboundTexts(p.agreed().hotel().id())).last().asString().startsWith("This booking has already been settled");
    }
}
