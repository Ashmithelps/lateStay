package com.latestay.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.TestBookings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** booking_confirmed and booking_update go only to travellers who ticked the WhatsApp box. */
class TravellerUpdatesIT extends WhatsAppItSupport {

    static final double LAT = 32.0;
    static final String TRAVELLER = "919876543210";

    List<Map<String, Object>> travellerRows(UUID bookingId) {
        return jdbc.sql("""
                        SELECT wa_id, template, payload::text AS payload FROM whatsapp_message
                        WHERE booking_id = :b AND audience = 'TRAVELLER' ORDER BY id""")
                .param("b", bookingId).query().listOfRows();
    }

    List<String> bodyValues(Map<String, Object> row) {
        JsonNode message = read(row.get("payload"));
        List<String> values = new ArrayList<>();
        message.path("template").path("components").get(0).path("parameters")
                .forEach(v -> values.add(v.path("text").asText()));
        return values;
    }

    BookingCreated paid(boolean optedIn) {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = optedIn ? fixtures.bookWithUpdates(agreed) : fixtures.book(agreed);
        fixtures.pay(created);
        return created;
    }

    @Test
    void theOptInIsStoredWithItsTime() {
        BookingCreated yes = paid(true);
        BookingCreated no = paid(false);
        assertThat(jdbc.sql("SELECT whatsapp_updates_at FROM booking WHERE id = :id").param("id", yes.bookingId())
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(clock.instant());
        assertThat(jdbc.sql("SELECT whatsapp_updates_at FROM booking WHERE id = :id").param("id", no.bookingId())
                .query(java.time.OffsetDateTime.class).optional()).isEmpty();
    }

    @Test
    void confirmingSendsTheBookingPassToAnOptedInTraveller() {
        BookingCreated created = paid(true);
        bookings.confirm(created.bookingId());

        Map<String, Object> row = travellerRows(created.bookingId()).getFirst();
        assertThat(row).containsEntry("wa_id", TRAVELLER).containsEntry("template", "booking_confirmed");
        String code = jdbc.sql("SELECT code FROM booking WHERE id = :id").param("id", created.bookingId())
                .query(String.class).single();
        assertThat(bodyValues(row)).containsExactly("Booking Lodge", "1 Test Road", "12:30 am", "800", code,
                "https://latestay.test/pass/" + created.bookingId(), "https://wa.me/919000022222");
    }

    @Test
    void travellersWhoDidNotTickTheBoxGetNothing() throws Exception {
        BookingCreated created = paid(false);
        bookings.confirm(created.bookingId());
        BookingCreated declined = paid(false);
        bookings.decline(declined.bookingId());
        jobs.issueRefunds();

        assertThat(travellerRows(created.bookingId())).isEmpty();
        assertThat(travellerRows(declined.bookingId())).isEmpty();
    }

    @Test
    void aDeclineAndItsRefundEachSendOneUpdate() {
        BookingCreated created = paid(true);
        bookings.decline(created.bookingId());
        jobs.issueRefunds();
        jobs.issueRefunds();

        List<Map<String, Object>> rows = travellerRows(created.bookingId());
        assertThat(rows).extracting(r -> r.get("template")).containsExactly("booking_update", "booking_update");
        assertThat(bodyValues(rows.get(0))).containsExactly(
                "Booking Lodge couldn't take your booking, so your ₹200 deposit is being refunded in full",
                "https://latestay.test/pass/" + created.bookingId());
        assertThat(bodyValues(rows.get(1)).getFirst()).isEqualTo("your ₹200 refund has been processed");
    }

    @Test
    void cancellingAfterPaymentTimingOutAndRescueSendUpdates() throws Exception {
        BookingCreated cancelled = paid(true);
        mvc.perform(post("/api/bookings/{id}/cancel", cancelled.bookingId()));
        assertThat(bodyValues(travellerRows(cancelled.bookingId()).getFirst()).getFirst())
                .isEqualTo("your booking at Booking Lodge is cancelled and your ₹200 deposit is being refunded");

        BookingCreated timedOut = paid(true);
        clock.advance(Duration.ofMinutes(6));
        jobs.timeoutUnconfirmed();
        assertThat(bodyValues(travellerRows(timedOut.bookingId()).getFirst()).getFirst())
                .isEqualTo("Booking Lodge didn't confirm in time, so your ₹200 deposit is being refunded in full");

        BookingCreated rescued = paid(true);
        bookings.confirm(rescued.bookingId());
        bookings.rescue(rescued.bookingId());
        assertThat(travellerRows(rescued.bookingId())).extracting(r -> r.get("template"))
                .containsExactly("booking_confirmed", "booking_update");
        assertThat(bodyValues(travellerRows(rescued.bookingId()).get(1)).getFirst()).isEqualTo(
                "we're sorry Booking Lodge let you down; your ₹200 is being refunded and your pass lists nearby "
                        + "hotels with rooms");
    }

    @Test
    void anUnpaidCancelSendsNothing() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.bookWithUpdates(agreed);
        mvc.perform(post("/api/bookings/{id}/cancel", created.bookingId()));
        assertThat(travellerRows(created.bookingId())).isEmpty();
    }

    @Test
    void theTravellersNumberIsRedactedFromTheirMessagesWithTheBookingsPhone() {
        BookingCreated created = paid(true);
        bookings.confirm(created.bookingId());
        clock.advance(Duration.ofDays(91));
        jobs.deleteOldPhoneNumbers();

        assertThat(travellerRows(created.bookingId())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("wa_id", "deleted");
            assertThat((String) row.get("payload")).doesNotContain("98765");
        });
    }
}
