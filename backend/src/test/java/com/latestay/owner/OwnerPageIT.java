package com.latestay.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.TestBookings;
import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.ledger.Settlements;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** The owner page: magic-link sign-in, expiry, and that one owner can never see or change another hotel. */
class OwnerPageIT extends BookingItSupport {

    static final double LAT = 34.0;

    @Autowired
    OwnerSessions sessions;
    @Autowired
    FloorRuleRepository floorRules;
    @Autowired
    Settlements settlements;

    Cookie signIn(UUID hotelId) throws Exception {
        String token = sessions.newLinkToken(hotelId);
        return mvc.perform(post("/api/owner/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"" + token + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(OwnerSessions.COOKIE);
    }

    FloorRule floorOf(TestBookings.Agreed agreed) {
        return floorRules.forRoomType(agreed.room().id()).getFirst();
    }

    @Test
    void aLinkSignsInOnceWithAThirtyDayHttpOnlyCookieForThatHotel() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        String token = sessions.newLinkToken(agreed.hotel().id());
        String body = "{\"token\": \"" + token + "\"}";

        var response = mvc.perform(post("/api/owner/session").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotelName").value("Booking Lodge"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("ls_owner="),
                        org.hamcrest.Matchers.containsString("Path=/api/owner"),
                        org.hamcrest.Matchers.containsString("Max-Age=2592000"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"))))
                .andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).doesNotContain(agreed.hotel().id().toString());

        // Single use.
        mvc.perform(post("/api/owner/session").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_SIGNED_IN"));
        assertThat(jdbc.sql("SELECT count(*) FROM owner_login_token WHERE token_hash = :t").param("t", token)
                .query(Integer.class).single()).as("only the hash is stored").isZero();
    }

    @Test
    void aLinkExpiresAfterFifteenMinutes() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        String early = sessions.newLinkToken(agreed.hotel().id());
        String late = sessions.newLinkToken(agreed.hotel().id());

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        mvc.perform(post("/api/owner/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + early + "\"}")).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(1));
        mvc.perform(post("/api/owner/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + late + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/owner/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"not-a-token\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSessionLastsThirtyDaysAndSigningOutEndsIt() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        Cookie cookie = signIn(agreed.hotel().id());
        clock.advance(Duration.ofDays(29));
        mvc.perform(get("/api/owner/summary").cookie(cookie)).andExpect(status().isOk());
        clock.advance(Duration.ofDays(1));
        mvc.perform(get("/api/owner/summary").cookie(cookie)).andExpect(status().isUnauthorized());

        Cookie other = signIn(agreed.hotel().id());
        mvc.perform(post("/api/owner/logout").cookie(other)).andExpect(status().isNoContent());
        mvc.perform(get("/api/owner/summary").cookie(other)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutAValidCookieNothingIsShown() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        mvc.perform(get("/api/owner/summary")).andExpect(status().isUnauthorized());
        Cookie cookie = signIn(agreed.hotel().id());
        String forged = UUID.randomUUID() + cookie.getValue().substring(cookie.getValue().indexOf('.'));
        mvc.perform(get("/api/owner/summary").cookie(new Cookie(OwnerSessions.COOKIE, forged)))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/owner/rooms-tonight").contentType(MediaType.APPLICATION_JSON).content("{\"rooms\": 4}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oneOwnerCanNeverSeeOrChangeAnotherHotel() throws Exception {
        TestBookings.Agreed mine = fixtures.agreed(LAT, 1400, 700, 3);
        TestBookings.Agreed theirs = fixtures.agreed(LAT, 1600, 800, 2);
        BookingCreated theirBooking = fixtures.book(theirs);
        fixtures.pay(theirBooking);
        bookings.confirm(theirBooking.bookingId());
        bookings.checkInByOwner(theirBooking.bookingId());
        clock.set(Instant.parse("2026-11-16T04:30:00Z"));
        settlements.settleWeek();
        UUID theirSettlement = settlements.forHotel(theirs.hotel().id(), 1).getFirst().id();
        clock.set(Instant.parse("2026-11-11T17:00:00Z"));

        Cookie cookie = signIn(mine.hotel().id());
        String summary = mvc.perform(get("/api/owner/summary").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings.length()").value(0))
                .andExpect(jsonPath("$.settlements.length()").value(0))
                .andExpect(jsonPath("$.rooms[0].id").value(mine.room().id().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(summary).doesNotContain(theirs.hotel().id().toString()).doesNotContain(theirs.room().id().toString());

        // Their floor rule, their settlement: not found, and unchanged.
        FloorRule theirFloor = floorOf(theirs);
        mvc.perform(put("/api/owner/floor-rules/{id}", theirFloor.id()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"floorPrice\": 500}"))
                .andExpect(status().isNotFound());
        assertThat(floorRules.find(theirFloor.id()).orElseThrow().floorPrice()).isEqualTo(800);
        mvc.perform(get("/api/owner/settlements/{id}/lines", theirSettlement).cookie(cookie))
                .andExpect(status().isNotFound());

        // Rooms tonight change only my hotel; a hotel id in the body is ignored (there's no field for it).
        mvc.perform(put("/api/owner/rooms-tonight").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rooms\": 4, \"hotelId\": \"" + theirs.hotel().id() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roomsFree").value(4));
        assertThat(availability.roomsFree(mine.hotel().id(), TestBookings.NIGHT)).isEqualTo(4);
        assertThat(availability.roomsFree(theirs.hotel().id(), TestBookings.NIGHT)).isEqualTo(2);

        // Their own page shows their booking and settlement.
        Cookie theirCookie = signIn(theirs.hotel().id());
        mvc.perform(get("/api/owner/summary").cookie(theirCookie))
                .andExpect(jsonPath("$.bookings[0].guestName").value("Asha Verma"))
                .andExpect(jsonPath("$.bookings[0].checkedIn").value(true))
                .andExpect(jsonPath("$.settlements[0].amountPaise").value(10_000));
        mvc.perform(get("/api/owner/settlements/{id}/lines", theirSettlement).cookie(theirCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void tonightsBookingsEarningsAndNextPayout() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated arrived = fixtures.book(agreed);
        fixtures.pay(arrived);
        bookings.confirm(arrived.bookingId());
        bookings.checkInByOwner(arrived.bookingId());
        BookingCreated coming = fixtures.book(fixtures.agreedAnother(agreed));
        fixtures.pay(coming);
        bookings.confirm(coming.bookingId());

        Cookie cookie = signIn(agreed.hotel().id());
        mvc.perform(get("/api/owner/summary").cookie(cookie))
                .andExpect(jsonPath("$.tonight").value("2026-11-11"))
                .andExpect(jsonPath("$.roomsFree").value(3))
                .andExpect(jsonPath("$.bookings.length()").value(2))
                // Both arrive at 23:30 and were made at the same (test clock) moment: look them up by id.
                .andExpect(jsonPath("$.bookings[?(@.id == '%s')].guests".formatted(arrived.bookingId())).value(2))
                .andExpect(jsonPath("$.bookings[?(@.id == '%s')].rooms".formatted(arrived.bookingId())).value(1))
                .andExpect(jsonPath("$.bookings[?(@.id == '%s')].arriveAt".formatted(arrived.bookingId()))
                        .value("2026-11-11T23:30:00"))
                .andExpect(jsonPath("$.bookings[?(@.id == '%s')].checkedIn".formatted(arrived.bookingId()))
                        .value(true))
                .andExpect(jsonPath("$.bookings[?(@.id == '%s')].status".formatted(arrived.bookingId()))
                        .value("CHECKED_IN"))
                .andExpect(jsonPath("$.bookings[?(@.id == '%s')].checkedIn".formatted(coming.bookingId()))
                        .value(false))
                .andExpect(jsonPath("$.bookings[?(@.id == '%s')].status".formatted(coming.bookingId()))
                        .value("CONFIRMED"))
                .andExpect(jsonPath("$.reliability.confirmed").value(2))
                .andExpect(jsonPath("$.reliability.confirmRatePercent").value(100))
                .andExpect(jsonPath("$.reliability.isNew").value(true))
                .andExpect(jsonPath("$.reviews.rating.count").value(0))
                .andExpect(jsonPath("$.earnings.weekStart").value("2026-11-09"))
                .andExpect(jsonPath("$.earnings.thisWeekPaise").value(10_000))
                .andExpect(jsonPath("$.earnings.owedPaise").value(10_000))
                .andExpect(jsonPath("$.earnings.nextPayoutDate").value("2026-11-16"));
    }

    @Test
    void floorPricesAreEditableUpToTheListPrice() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        Cookie cookie = signIn(agreed.hotel().id());
        FloorRule floor = floorOf(agreed);

        mvc.perform(put("/api/owner/floor-rules/{id}", floor.id()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"floorPrice\": 1401}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/owner/floor-rules/{id}", floor.id()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"floorPrice\": 0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/owner/floor-rules/{id}", floor.id()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"floorPrice\": 1400}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.floorPrice").value(1400));
        FloorRule after = floorRules.find(floor.id()).orElseThrow();
        assertThat(after.floorPrice()).isEqualTo(1400);
        assertThat(after.weekdays()).isEqualTo(floor.weekdays());
        assertThat(after.slotType()).isEqualTo(floor.slotType());
    }

    @Test
    void roomsTonightMustBeZeroToFifty() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        Cookie cookie = signIn(agreed.hotel().id());
        mvc.perform(put("/api/owner/rooms-tonight").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rooms\": 51}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/owner/rooms-tonight").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rooms\": 0}")).andExpect(status().isOk()).andExpect(jsonPath("$.roomsAvailable").value(0));
        assertThat(jdbc.sql("SELECT source FROM nightly_availability WHERE hotel_id = :h AND night_of = :n")
                .param("h", agreed.hotel().id()).param("n", TestBookings.NIGHT).query(String.class).single())
                .isEqualTo("OWNER_PAGE");
    }
}
