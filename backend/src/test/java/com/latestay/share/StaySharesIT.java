package com.latestay.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.booking.Booking;
import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.BookingRepository;
import com.latestay.booking.TestBookings;
import com.latestay.hotel.ConflictException;
import com.latestay.share.ShareDtos.SharedStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** "Share with family": the stay ends 09:00 IST on 12 Nov, so links work until 09:00 IST on 13 Nov. */
class StaySharesIT extends BookingItSupport {

    static final double LAT = 41.0;
    static final Instant LINK_ENDS = Instant.parse("2026-11-13T03:30:00Z");

    @Autowired
    StayShares shares;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    ObjectMapper json;

    BookingCreated paid() {
        BookingCreated created = fixtures.book(fixtures.agreed(LAT, 1400, 700, 3));
        fixtures.pay(created);
        return created;
    }

    JsonNode shared(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/stays/{token}", token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void theSharedPageShowsTheHotelArrivalAndStatusAndNothingPrivate() throws Exception {
        BookingCreated created = paid();
        String body = mvc.perform(post("/api/bookings/{id}/share", created.bookingId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value("2026-11-13T03:30:00Z"))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).path("token").asText();
        assertThat(json.readTree(body).path("url").asText()).isEqualTo("https://latestay.test/stay/" + token);
        assertThat(token).matches("[A-Za-z0-9_-]{32}");

        JsonNode page = shared(token);
        assertThat(page.path("hotelName").asText()).isEqualTo("Booking Lodge");
        assertThat(page.path("address").asText()).isEqualTo("1 Test Road");
        assertThat(page.path("arriveAround").asText()).isEqualTo("2026-11-11T23:30:00");
        assertThat(page.path("status").asText()).isEqualTo("WAITING");
        assertThat(page.has("lat")).isTrue();

        bookings.confirm(created.bookingId());
        assertThat(shared(token).path("status").asText()).isEqualTo("CONFIRMED");
        bookings.checkInByOwner(created.bookingId());
        String raw = mvc.perform(get("/api/stays/{token}", token)).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(raw).path("status").asText()).isEqualTo("CHECKED_IN");

        // No desk code, phone numbers (guest's or hotel's), guest name, or any amount.
        Booking b = bookingRepository.find(created.bookingId()).orElseThrow();
        assertThat(raw).doesNotContain(b.code(), "98765", "9876543210", "Asha", "₹", "price", "deposit", "desk",
                "phone", "whatsapp", String.valueOf(b.agreedPrice()), String.valueOf(b.payAtDesk()));
        // Apart from the map position and the times, no run of 4 digits at all (a code or a phone number).
        String withoutPositionAndTimes = raw.replaceAll("\"(lat|lng)\":-?[0-9.]+", "")
                .replaceAll("\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z?\"", "");
        assertThat(withoutPositionAndTimes).doesNotContainPattern("[0-9]{4}");
    }

    @Test
    void theLinkExpiresADayAfterTheLeaveTime() throws Exception {
        BookingCreated created = paid();
        String token = shares.create(created.bookingId()).token();
        clock.set(LINK_ENDS.minusSeconds(1));
        assertThat(shares.view(token).status()).isEqualTo(SharedStatus.WAITING);
        clock.set(LINK_ENDS);
        assertThatThrownBy(() -> shares.view(token)).isInstanceOf(StayShareGoneException.class)
                .hasMessage("This link has expired.");
        mvc.perform(get("/api/stays/{token}", token)).andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("EXPIRED"));
        mvc.perform(get("/api/bookings/{id}/share", created.bookingId())).andExpect(status().isNoContent());
    }

    @Test
    void theTravellerCanTurnItOffAndANewLinkReplacesTheOld() throws Exception {
        BookingCreated created = paid();
        String first = shares.create(created.bookingId()).token();
        String second = shares.create(created.bookingId()).token();
        assertThat(second).isNotEqualTo(first);
        mvc.perform(get("/api/stays/{token}", first)).andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("TURNED_OFF"));
        mvc.perform(get("/api/bookings/{id}/share", created.bookingId()))
                .andExpect(jsonPath("$.token").value(second));

        mvc.perform(delete("/api/bookings/{id}/share", created.bookingId())).andExpect(status().isNoContent());
        mvc.perform(get("/api/stays/{token}", second)).andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("TURNED_OFF"));
        mvc.perform(get("/api/stays/{token}", "no-such-token-aaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAPaidStayCanBeShared() {
        BookingCreated unpaid = fixtures.book(fixtures.agreed(LAT, 1400, 700, 3));
        assertThatThrownBy(() -> shares.create(unpaid.bookingId())).isInstanceOf(ConflictException.class);

        BookingCreated cancelled = paid();
        String token = shares.create(cancelled.bookingId()).token();
        bookings.cancel(cancelled.bookingId());
        assertThat(shares.view(token).status()).isEqualTo(SharedStatus.ENDED);
        assertThatThrownBy(() -> shares.create(cancelled.bookingId())).isInstanceOf(ConflictException.class);

    }
}
