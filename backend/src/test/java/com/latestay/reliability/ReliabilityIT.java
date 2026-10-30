package com.latestay.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.DisputeOutcome;
import com.latestay.booking.TestBookings;
import com.latestay.conversation.ConversationService;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.RoomType;
import com.latestay.negotiation.SlotType;
import com.latestay.session.NegotiationService;
import com.latestay.stay.SearchResponse.HotelResult;
import com.latestay.stay.SearchService;
import com.latestay.stay.StayRequest;
import com.latestay.stay.StayRequestRepository;
import com.latestay.stay.StayWindow;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Confirm rate, median minutes to confirm, rescues, upheld disputes; pausing; ranking. Deposit 200. */
class ReliabilityIT extends BookingItSupport {

    static final double LAT = 38.0;
    static final double LNG = 71.0;
    static final double KM = 1 / 111.0;
    static int nextArea = 0;

    @Autowired
    Reliability reliability;
    @Autowired
    SearchService search;
    @Autowired
    NegotiationService negotiations;
    @Autowired
    ConversationService conversations;
    @Autowired
    StayRequestRepository stayRequests;

    record Lodge(Hotel hotel, RoomType room) {
    }

    /** A hotel with 20 rooms free tonight, in its own spot (0.5 km north of the area's centre unless moved). */
    Lodge lodge(double lat, String name) {
        Hotel hotel = hotels.hotel(name, lat, LNG, List.of(), new HotelPolicies(false, false, null), true);
        RoomType room = hotels.room(hotel, 1400, 3, 700, SlotType.LATE_NIGHT);
        hotels.roomsFree(hotel, TestBookings.NIGHT, 20);
        return new Lodge(hotel, room);
    }

    Lodge lodge() {
        return lodge(LAT + (nextArea++) * 0.3, "Reliable Lodge");
    }

    /** A paid booking at the lodge, the deposit paid now. */
    UUID paid(Lodge lodge) {
        StayRequest stay = stayRequests.insert(lodge.hotel().lat(), lodge.hotel().lng(),
                StayWindow.of(TestBookings.ARRIVE, TestBookings.LEAVE), 2, List.of());
        UUID session = negotiations.create(stay.id(), lodge.room().id()).id();
        long price = conversations.accept(session.toString()).offer();
        BookingCreated created = fixtures.book(new TestBookings.Agreed(lodge.hotel(), lodge.room(), stay, session,
                price));
        fixtures.pay(created);
        return created.bookingId();
    }

    UUID confirmedAfter(Lodge lodge, int minutes) {
        UUID id = paid(lodge);
        clock.advance(Duration.ofMinutes(minutes));
        bookings.confirm(id);
        return id;
    }

    void declined(Lodge lodge) {
        bookings.decline(paid(lodge));
    }

    @Test
    void numbersCoverConfirmRateMedianMinutesRescuesAndUpheldDisputes() {
        Lodge lodge = lodge();
        confirmedAfter(lodge, 2);
        confirmedAfter(lodge, 4);
        UUID third = confirmedAfter(lodge, 1);
        declined(lodge);
        UUID timedOut = paid(lodge);
        clock.advance(Duration.ofMinutes(6));
        bookings.timeoutIfUnconfirmed(timedOut);

        HotelReliability r = reliability.of(lodge.hotel().id());
        assertThat(r.confirmed()).isEqualTo(3);
        assertThat(r.declined()).isOne();
        assertThat(r.timedOut()).isOne();
        assertThat(r.confirmRatePercent()).isEqualTo(60);
        assertThat(r.medianMinutesToConfirm()).isEqualTo(2.0);
        assertThat(r.isNew()).isFalse();
        assertThat(r.score()).isEqualTo(60);

        bookings.rescue(third);
        r = reliability.of(lodge.hotel().id());
        assertThat(r.rescues()).isOne();
        assertThat(r.upheldDisputes()).isZero();
        assertThat(r.score()).isEqualTo(50);
    }

    @Test
    void anIDidArriveDisputeSettledAsStayedIsUpheld() {
        Lodge lodge = lodge();
        UUID id = confirmedAfter(lodge, 1);
        clock.set(TestBookings.ARRIVE.atZone(com.latestay.stay.LateStayTime.ZONE).toInstant()
                .plus(Duration.ofHours(2)));
        bookings.noShowByOwner(id);
        bookings.travellerArrived(id);
        bookings.settle(id, DisputeOutcome.GUEST_STAYED);

        HotelReliability r = reliability.of(lodge.hotel().id());
        assertThat(r.upheldDisputes()).isOne();
        assertThat(r.rescues()).isZero();
        assertThat(r.isNew()).isTrue();
        assertThat(r.score()).isEqualTo(HotelReliability.NEW_SCORE - 10);
    }

    @Test
    void aLowConfirmRatePausesTheHotelUntilAnAdminReviewsIt() throws Exception {
        Lodge lodge = lodge();
        confirmedAfter(lodge, 1);
        declined(lodge);
        assertThat(reliability.paused(lodge.hotel().id())).isFalse(); // only 2 decisions so far
        declined(lodge);
        assertThat(reliability.paused(lodge.hotel().id())).isTrue(); // 1 of 3 confirmed

        assertThat(searchAt(lodge.hotel())).extracting(HotelResult::id).doesNotContain(lodge.hotel().id());
        StayRequest stay = stayRequests.insert(lodge.hotel().lat(), lodge.hotel().lng(),
                StayWindow.of(TestBookings.ARRIVE, TestBookings.LEAVE), 2, List.of());
        assertThatThrownBy(() -> negotiations.create(stay.id(), lodge.room().id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("isn't taking bookings right now");

        mvc.perform(post("/api/admin/hotels/{id}/reliability-reviewed", lodge.hotel().id())
                .with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))).andExpect(status().isNoContent());
        assertThat(reliability.paused(lodge.hotel().id())).isFalse();
        assertThat(searchAt(lodge.hotel())).extracting(HotelResult::id).contains(lodge.hotel().id());

        // The numbers shown still cover the whole window, but only incidents after the review can pause it again.
        clock.advance(Duration.ofSeconds(1));
        declined(lodge);
        assertThat(reliability.of(lodge.hotel().id()).declined()).isEqualTo(3);
        assertThat(reliability.paused(lodge.hotel().id())).isFalse();
    }

    @Test
    void twoRescuesPauseTheHotel() {
        Lodge lodge = lodge();
        bookings.rescue(confirmedAfter(lodge, 1));
        assertThat(reliability.paused(lodge.hotel().id())).isFalse();
        bookings.rescue(confirmedAfter(lodge, 1));
        assertThat(reliability.paused(lodge.hotel().id())).isTrue();
    }

    List<HotelResult> searchAt(Hotel hotel) {
        return search.search(hotel.lat(), hotel.lng(), StayWindow.of(TestBookings.ARRIVE, TestBookings.LEAVE), 2,
                List.of()).results();
    }

    @Test
    void searchRanksByDistanceBandThenReliabilityThenPrice() {
        double centre = LAT + 40 * 0.3;
        Lodge steady = lodge(centre + 0.5 * KM, "Steady");
        Lodge fresh = lodge(centre + 0.6 * KM, "Fresh");
        Lodge shaky = lodge(centre + 0.4 * KM, "Shaky");
        Lodge farSteady = lodge(centre + 2 * KM, "Far Steady");
        for (int i = 0; i < 3; i++) {
            confirmedAfter(steady, 1);
            confirmedAfter(shaky, 1);
            confirmedAfter(farSteady, 1);
        }
        UUID late = paid(shaky);
        clock.advance(Duration.ofMinutes(6));
        bookings.timeoutIfUnconfirmed(late); // 3 of 4: 75%

        List<String> names = search.search(centre, LNG, StayWindow.of(TestBookings.ARRIVE, TestBookings.LEAVE), 2,
                List.of()).results().stream().map(HotelResult::name).toList();
        // All under 1 km first: 100, then new (80), then 75 (although Shaky is nearest); then 1-3 km.
        assertThat(names).containsExactly("Steady", "Fresh", "Shaky", "Far Steady");
    }
}
