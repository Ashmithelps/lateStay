package com.latestay.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.InvalidBookingException;
import com.latestay.booking.TestBookings;
import com.latestay.hotel.ConflictException;
import com.latestay.review.ReviewDtos.ReviewBody;
import com.latestay.review.ReviewDtos.ReviewStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Reviews, the rating rules and the 11:00 review invite. Stays are 23:30 on 11 Nov to 09:00 on 12 Nov (IST). */
class ReviewsIT extends BookingItSupport {

    static final double LAT = 37.0;
    /** 09:00 IST on 12 Nov, the stay's leave time. */
    static final Instant LEAVE = Instant.parse("2026-11-12T03:30:00Z");

    @Autowired
    Reviews reviews;
    @Autowired
    ReviewInvites invites;

    /** A booking the guest checked in to. */
    UUID stayed(TestBookings.Agreed agreed, boolean updates) {
        BookingCreated created = updates ? fixtures.bookWithUpdates(agreed) : fixtures.book(agreed);
        fixtures.pay(created);
        bookings.confirm(created.bookingId());
        bookings.checkInByOwner(created.bookingId());
        return created.bookingId();
    }

    static ReviewBody body(int stars, String text, ReviewTag... tags) {
        return new ReviewBody(stars, List.of(tags), text);
    }

    @Test
    void aStayCanBeRatedOnceFromTheLeaveTimeForFourteenDays() {
        UUID booking = stayed(fixtures.agreed(LAT, 1400, 700, 3), false);
        assertThat(reviews.state(booking).status()).isEqualTo(ReviewStatus.NOT_YET);
        assertThatThrownBy(() -> reviews.submit(booking, body(5, null))).isInstanceOf(InvalidBookingException.class);

        clock.set(LEAVE);
        assertThat(reviews.state(booking).status()).isEqualTo(ReviewStatus.OPEN);
        assertThat(reviews.state(booking).closesAt()).isEqualTo(LEAVE.plus(Duration.ofDays(14)));
        reviews.submit(booking, body(4, "  Warm room, quick check-in  ", ReviewTag.WARM, ReviewTag.WARM,
                ReviewTag.FRIENDLY_DESK));

        var state = reviews.state(booking);
        assertThat(state.status()).isEqualTo(ReviewStatus.DONE);
        assertThat(state.review().text()).isEqualTo("Warm room, quick check-in");
        assertThat(state.review().tags()).containsExactly(ReviewTag.WARM, ReviewTag.FRIENDLY_DESK);
        assertThatThrownBy(() -> reviews.submit(booking, body(1, null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void reviewsCloseAfterFourteenDays() {
        UUID booking = stayed(fixtures.agreed(LAT, 1400, 700, 3), false);
        clock.set(LEAVE.plus(Duration.ofDays(14)).plusSeconds(1));
        assertThat(reviews.state(booking).status()).isEqualTo(ReviewStatus.CLOSED);
        assertThatThrownBy(() -> reviews.submit(booking, body(5, null))).isInstanceOf(InvalidBookingException.class);
    }

    @Test
    void onlyStaysThatHappenedCanBeRated() {
        BookingCreated created = fixtures.book(fixtures.agreed(LAT, 1400, 700, 3));
        fixtures.pay(created);
        bookings.decline(created.bookingId());
        clock.set(LEAVE);
        assertThat(reviews.state(created.bookingId()).status()).isEqualTo(ReviewStatus.NOT_ELIGIBLE);
        assertThatThrownBy(() -> reviews.submit(created.bookingId(), body(1, "never got a room")))
                .isInstanceOf(InvalidBookingException.class);
    }

    @Test
    void textIsAtMost300CharactersWithNoPhoneNumbers() {
        UUID booking = stayed(fixtures.agreed(LAT, 1400, 700, 3), false);
        clock.set(LEAVE);
        assertThatThrownBy(() -> reviews.submit(booking, body(3, "a".repeat(301))))
                .hasMessage("Please keep it to 300 characters.");
        assertThatThrownBy(() -> reviews.submit(booking, body(3, "Call the owner on 98765 43210")))
                .hasMessage("Please leave out phone numbers.");
        assertThatThrownBy(() -> reviews.submit(booking, body(3, "ring +91-98765-43210 for rooms")))
                .hasMessage("Please leave out phone numbers.");
        reviews.submit(booking, body(3, "a".repeat(300)));
        assertThat(reviews.state(booking).review().text()).hasSize(300);
    }

    @Test
    void theApiRejectsUnknownTagsAndStarsOutOfRange() throws Exception {
        UUID booking = stayed(fixtures.agreed(LAT, 1400, 700, 3), false);
        clock.set(LEAVE);
        mvc.perform(post("/api/bookings/{id}/review", booking).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stars\": 6, \"tags\": []}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/bookings/{id}/review", booking).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stars\": 4, \"tags\": [\"CHEAP\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/bookings/{id}/review", booking).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\": 4, \"tags\": [\"CLEAN\", \"AS_SHOWN\"], \"text\": \"Spotless\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tags[1]").value("AS_SHOWN"));
        mvc.perform(get("/api/bookings/{id}/review", booking))
                .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.review.stars").value(4));
    }

    @Test
    void theHotelShowsNewUntilThreeReviewsThenTheBayesianAverage() throws Exception {
        TestBookings.Agreed first = fixtures.agreed(LAT, 1400, 700, 4);
        UUID a = stayed(first, false);
        UUID b = stayed(fixtures.agreedAnother(first), false);
        UUID c = stayed(fixtures.agreedAnother(first), false);
        clock.set(LEAVE);
        reviews.submit(a, body(5, "Lovely", ReviewTag.CLEAN));
        reviews.submit(b, body(5, null, ReviewTag.CLEAN, ReviewTag.QUIET));
        assertThat(reviews.forHotel(first.hotel().id()).rating().value()).isNull();
        assertThat(reviews.forHotel(first.hotel().id()).rating().count()).isEqualTo(2);

        reviews.submit(c, body(5, "Would stay again"));
        var page = reviews.forHotel(first.hotel().id());
        assertThat(page.rating().value()).isEqualByComparingTo("4.4"); // (5 x 4 + 15) / 8
        assertThat(page.rating().count()).isEqualTo(3);
        assertThat(page.tags()).extracting(t -> t.tag() + " " + t.count()).containsExactly("CLEAN 2", "QUIET 1");
        assertThat(page.recent()).hasSize(3);
        assertThat(page.recent().getFirst().stayedIn()).hasToString("2026-11");

        mvc.perform(get("/api/hotels/{slug}", first.hotel().slug()))
                .andExpect(jsonPath("$.reviews.rating.value").value(4.4))
                .andExpect(jsonPath("$.reviews.rating.count").value(3));
    }

    @Test
    void anAdminCanHideAbusiveTextButTheStarsStillCount() throws Exception {
        TestBookings.Agreed first = fixtures.agreed(LAT, 1400, 700, 4);
        UUID a = stayed(first, false);
        UUID b = stayed(fixtures.agreedAnother(first), false);
        UUID c = stayed(fixtures.agreedAnother(first), false);
        clock.set(LEAVE);
        reviews.submit(a, body(1, "Something abusive"));
        reviews.submit(b, body(5, null));
        reviews.submit(c, body(5, null));
        UUID abusive = reviews.latest(500).stream().filter(r -> r.bookingId().equals(a)).findFirst().orElseThrow()
                .id();

        mvc.perform(post("/api/admin/reviews/{id}/hide-text", abusive).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isNoContent());
        var page = reviews.forHotel(first.hotel().id());
        assertThat(page.recent()).extracting(r -> r.text()).doesNotContain("Something abusive");
        assertThat(page.rating().value()).isEqualByComparingTo("3.9"); // (20 + 11) / 8, the 1 star still counts
        assertThat(reviews.latest(500)).filteredOn(r -> r.id().equals(abusive))
                .singleElement().satisfies(r -> assertThat(r.textHidden()).isTrue());

        mvc.perform(post("/api/admin/reviews/{id}/show-text", abusive).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isNoContent());
        assertThat(reviews.forHotel(first.hotel().id()).recent()).extracting(r -> r.text())
                .contains("Something abusive");
        mvc.perform(post("/api/admin/reviews/{id}/hide-text", abusive)).andExpect(status().isUnauthorized());
    }

    int invitesFor(UUID booking) {
        return jdbc.sql("SELECT count(*) FROM whatsapp_message WHERE booking_id = :b AND template = 'booking_update'")
                .param("b", booking).query(Integer.class).single();
    }

    @Test
    void optedInTravellersGetOneInviteAtElevenAfterTheirStay() {
        TestBookings.Agreed first = fixtures.agreed(LAT, 1400, 700, 4);
        UUID optedIn = stayed(first, true);
        UUID notOptedIn = stayed(fixtures.agreedAnother(first), false);
        UUID reviewed = stayed(fixtures.agreedAnother(first), true);

        clock.set(LEAVE);
        reviews.submit(reviewed, body(4, null));
        // 11:00 IST on the day the stay ended.
        clock.set(Instant.parse("2026-11-12T05:30:00Z"));
        bookings.completeIfLeft(optedIn);
        invites.send();
        invites.send();

        assertThat(invitesFor(optedIn)).isOne();
        assertThat(invitesFor(notOptedIn)).isZero();
        assertThat(invitesFor(reviewed)).isZero();
        String payload = jdbc.sql("SELECT payload::text FROM whatsapp_message WHERE booking_id = :b "
                        + "AND template = 'booking_update'").param("b", optedIn).query(String.class).single();
        assertThat(payload).contains("how was your stay at Booking Lodge? You can rate it in a few taps from your pass")
                .contains("https://latestay.test/pass/" + optedIn + "/review");

        // A day later the stay is no longer "just ended": nothing more.
        clock.set(Instant.parse("2026-11-13T05:30:00Z"));
        jdbc.sql("DELETE FROM whatsapp_message WHERE booking_id = :b").param("b", optedIn).update();
        invites.send();
        assertThat(invitesFor(optedIn)).isZero();
    }
}
