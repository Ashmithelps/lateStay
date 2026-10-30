package com.latestay.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.TestBookings;
import com.latestay.conversation.ConversationService;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.RoomType;
import com.latestay.negotiation.SlotType;
import com.latestay.session.NegotiationCreated;
import com.latestay.session.NegotiationService;
import com.latestay.stay.SearchService;
import com.latestay.stay.StayRequest;
import com.latestay.stay.StayRequestRepository;
import com.latestay.stay.StayWindow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Search, the hotel's "from" price and new negotiations all open at the engine's occupancy price. List ₹1,400,
 * floor ₹700, 10 rooms in all, arriving 23:30 (70% of list by time alone) on a Wednesday unless said otherwise.
 * Hotels sit on their own longitude, away from other test classes' hotels.
 */
class PricingInputsIT extends OccupancyPricingSupport {

    static final double LNG = 72.0;
    static final AtomicInteger NEXT = new AtomicInteger();

    @Autowired
    SearchService search;
    @Autowired
    NegotiationService negotiations;
    @Autowired
    ConversationService conversations;
    @Autowired
    StayRequestRepository stayRequests;
    @Autowired
    Seasons seasons;

    record Lodge(Hotel hotel, RoomType room, String town) {
    }

    /** A hotel in a town of its own (so its seasons touch no other test), with rooms free on the night. */
    Lodge lodge(LocalDate night, int roomsFree) {
        int n = NEXT.incrementAndGet();
        Hotel hotel = hotels.hotel("Occupancy Lodge", 39.0 + n * 0.3, LNG, List.of(),
                new HotelPolicies(false, false, null), true);
        String town = "Pricetown " + n + " " + System.nanoTime();
        jdbc.sql("UPDATE hotel SET town = :town WHERE id = :id").param("town", town).param("id", hotel.id()).update();
        RoomType room = hotels.room(hotel, 1400, 3, 700, SlotType.LATE_NIGHT);
        hotels.roomsFree(hotel, night, roomsFree);
        return new Lodge(hotel, room, town);
    }

    long fromPrice(Lodge lodge, LocalDateTime arrive) {
        return search.search(lodge.hotel().lat(), lodge.hotel().lng(), StayWindow.of(arrive, arrive.plusHours(9)), 2,
                List.of()).results().stream().filter(r -> r.id().equals(lodge.hotel().id())).findFirst()
                .orElseThrow().fromPrice();
    }

    static final LocalDateTime WEDNESDAY = TestBookings.ARRIVE;
    static final LocalDateTime FRIDAY = LocalDate.of(2026, 11, 13).atTime(23, 30);

    @Test
    void theLastRoomOpensNearListAndAHalfEmptyHotelLower() {
        assertThat(fromPrice(lodge(TestBookings.NIGHT, 1), WEDNESDAY)).isEqualTo(1350); // 95%
        assertThat(fromPrice(lodge(TestBookings.NIGHT, 2), WEDNESDAY)).isEqualTo(1150); // scarce: 80%
        assertThat(fromPrice(lodge(TestBookings.NIGHT, 4), WEDNESDAY)).isEqualTo(1000); // 70%
        assertThat(fromPrice(lodge(TestBookings.NIGHT, 6), WEDNESDAY)).isEqualTo(850); // half empty: 60%
    }

    @Test
    void weekendAndSeasonRaiseTheOpeningButNeverTheFloor() throws Exception {
        assertThat(fromPrice(lodge(FRIDAY.toLocalDate(), 4), FRIDAY)).isEqualTo(1100); // 980 x 110%

        Lodge peak = lodge(TestBookings.NIGHT, 4);
        mvc.perform(post("/api/admin/seasons").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"town": "%s", "startsOn": "2026-11-01", "endsOn": "2026-11-20",
                                 "multiplierPercent": 120, "label": "Diwali week"}""".formatted(peak.town())))
                .andExpect(status().isCreated());
        assertThat(fromPrice(peak, WEDNESDAY)).isEqualTo(1200); // 980 x 120% = 1,176

        Lodge quiet = lodge(TestBookings.NIGHT, 6);
        seasons.create(quiet.town(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 50, "Off season");
        // 840 x 70% (the clamp) = 588, but the floor is 700.
        assertThat(fromPrice(quiet, WEDNESDAY)).isEqualTo(700);
    }

    @Test
    void seasonsInOneTownCannotOverlap() {
        Lodge lodge = lodge(TestBookings.NIGHT, 4);
        seasons.create(lodge.town(), LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 31), 130, "Christmas");
        assertThatThrownBy(() -> seasons.create(lodge.town(), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 5),
                140, "New Year")).isInstanceOf(ConflictException.class);
        seasons.create(lodge.town(), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 5), 140, "New Year");
        assertThat(seasons.percentFor(lodge.town().toUpperCase(), LocalDate.of(2026, 12, 31))).isEqualTo(130);
        assertThat(seasons.percentFor(lodge.town(), LocalDate.of(2027, 1, 6))).isEqualTo(100);
    }

    @Test
    void aNegotiationOpensAtTheSearchPriceAndKeepsItsPricingInputs() {
        Lodge lodge = lodge(TestBookings.NIGHT, 2);
        long from = fromPrice(lodge, WEDNESDAY);
        StayRequest stay = stayRequests.insert(lodge.hotel().lat(), lodge.hotel().lng(),
                StayWindow.of(WEDNESDAY, WEDNESDAY.plusHours(9)), 2, List.of());
        NegotiationCreated created = negotiations.create(stay.id(), lodge.room().id());
        assertThat(created.currentOffer()).isEqualTo(from).isEqualTo(1150);

        Map<String, Object> row = jdbc.sql("""
                        SELECT pricing_version, rooms_free, total_rooms, demand_percent, floor_price
                        FROM negotiation_session WHERE id = :id""").param("id", created.id()).query().singleRow();
        assertThat(row).containsEntry("pricing_version", 2).containsEntry("rooms_free", 2)
                .containsEntry("total_rooms", 10).containsEntry("demand_percent", 100)
                .containsEntry("floor_price", 700);

        // Scarce rooms concede half as fast: 20% of the 1,150 - 700 gap (90) -> 1,100, not 1,000.
        var reply = conversations.handle(created.id().toString(), "100");
        assertThat(reply.offer()).isEqualTo(1100);
        // Rooms filling up after the session opened don't change it: it was rebuilt from the stored inputs.
        hotels.roomsFree(lodge.hotel(), TestBookings.NIGHT, 9);
        var next = conversations.handle(created.id().toString(), "100");
        assertThat(next.offer()).isLessThan(1100).isGreaterThanOrEqualTo(700);
    }
}
