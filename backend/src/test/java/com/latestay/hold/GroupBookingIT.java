package com.latestay.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingRepository;
import com.latestay.booking.BookingViews;
import com.latestay.booking.TestBookings;
import com.latestay.conversation.ConversationService;
import com.latestay.conversation.RoomGoneException;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.RoomType;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.PricingContext;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.negotiation.SlotType;
import com.latestay.pricing.OccupancyPricingSupport;
import com.latestay.session.NegotiationCreated;
import com.latestay.session.NegotiationService;
import com.latestay.stay.SearchResponse.HotelResult;
import com.latestay.stay.SearchResponse.RoomOption;
import com.latestay.stay.SearchService;
import com.latestay.stay.StayRequest;
import com.latestay.stay.StayRequestRepository;
import com.latestay.stay.StayWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Five guests at a hotel whose rooms sleep 2 (Twin: list 1,000, floor 500) and 3 (Family: list 1,500, floor 800):
 * no single room fits, so it offers two rooms, negotiated together (list 2,500, floor 1,300).
 */
class GroupBookingIT extends OccupancyPricingSupport {

    static final double LNG = 73.0;
    static final AtomicInteger NEXT = new AtomicInteger();
    static final StayWindow WINDOW = StayWindow.of(TestBookings.ARRIVE, TestBookings.LEAVE);

    @Autowired
    SearchService search;
    @Autowired
    NegotiationService negotiations;
    @Autowired
    ConversationService conversations;
    @Autowired
    StayRequestRepository stayRequests;
    @Autowired
    NegotiationEngine engine;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    BookingViews views;

    record Lodge(Hotel hotel, RoomType twin, RoomType family) {
    }

    Lodge lodge(int roomsFree) {
        Hotel hotel = hotels.hotel("Group Lodge", 40.0 + NEXT.incrementAndGet() * 0.3, LNG, List.of(),
                new HotelPolicies(false, false, null), true);
        RoomType twin = hotels.room(hotel, 1000, 2, 500, SlotType.LATE_NIGHT);
        jdbc.sql("UPDATE room_type SET name = 'Twin' WHERE id = :id").param("id", twin.id()).update();
        RoomType family = hotels.room(hotel, 1500, 3, 800, SlotType.LATE_NIGHT);
        jdbc.sql("UPDATE room_type SET name = 'Family' WHERE id = :id").param("id", family.id()).update();
        hotels.roomsFree(hotel, TestBookings.NIGHT, roomsFree);
        return new Lodge(hotel, twin, family);
    }

    StayRequest stay(Lodge lodge, int guests) {
        return stayRequests.insert(lodge.hotel().lat(), lodge.hotel().lng(), WINDOW, guests, List.of());
    }

    UUID openGroup(Lodge lodge) {
        return negotiations.create(stay(lodge, 5).id(), List.of(lodge.twin().id(), lodge.family().id())).id();
    }

    int available(Lodge lodge) {
        return availability.available(lodge.hotel().id(), TestBookings.NIGHT, clock.instant());
    }

    int activeHolds(Lodge lodge) {
        return jdbc.sql("SELECT count(*) FROM room_hold WHERE hotel_id = :h AND status = 'ACTIVE'")
                .param("h", lodge.hotel().id()).query(Integer.class).single();
    }

    @Test
    void searchOffersTwoRoomsWhenNoSingleRoomFits() {
        Lodge lodge = lodge(4);
        HotelResult result = search.search(lodge.hotel().lat(), lodge.hotel().lng(), WINDOW, 5, List.of()).results()
                .stream().filter(r -> r.id().equals(lodge.hotel().id())).findFirst().orElseThrow();
        RoomOption cheapest = result.roomTypes().getFirst();
        assertThat(cheapest.rooms()).isEqualTo(2);
        assertThat(cheapest.name()).isEqualTo("Twin + Family");
        assertThat(cheapest.maxGuests()).isEqualTo(5);
        assertThat(cheapest.roomTypeIds()).containsExactly(lodge.twin().id(), lodge.family().id());
        long expected = engine.openingOffer(PricingPolicy.of(2500, 1300, SlotType.LATE_NIGHT,
                PricingContext.of(4, 10, 100)), TestBookings.ARRIVE.toLocalTime());
        assertThat(cheapest.fromPrice()).isEqualTo(expected).isEqualTo(1750);
        // Family + Family sleeps 6 and is dearer; Twin + Twin sleeps only 4.
        assertThat(result.roomTypes()).extracting(RoomOption::name).containsExactly("Twin + Family", "Family + Family");

        // Two guests fit one room: no pairs.
        assertThat(search.search(lodge.hotel().lat(), lodge.hotel().lng(), WINDOW, 2, List.of()).results().stream()
                .filter(r -> r.id().equals(lodge.hotel().id())).findFirst().orElseThrow().roomTypes())
                .allSatisfy(o -> assertThat(o.rooms()).isOne());
        // One room free: no group offer at all.
        Lodge one = lodge(1);
        assertThat(search.search(one.hotel().lat(), one.hotel().lng(), WINDOW, 5, List.of()).results())
                .extracting(HotelResult::id).doesNotContain(one.hotel().id());
    }

    @Test
    void oneNegotiationCoversBothRoomsAndTheFloorIsTheSumOfBoth() {
        Lodge lodge = lodge(4);
        NegotiationCreated created = negotiations.create(stay(lodge, 5).id(),
                List.of(lodge.twin().id(), lodge.family().id()));
        assertThat(created.rooms()).isEqualTo(2);
        assertThat(created.roomType().name()).isEqualTo("Twin + Family");
        var row = jdbc.sql("SELECT list_price, floor_price, rooms FROM negotiation_session WHERE id = :id")
                .param("id", created.id()).query().singleRow();
        assertThat(row).containsEntry("list_price", 2500).containsEntry("floor_price", 1300)
                .containsEntry("rooms", 2);

        long last = created.currentOffer();
        for (int i = 0; i < 6; i++) {
            try {
                last = conversations.handle(created.id().toString(), "100").offer();
            } catch (RuntimeException closed) {
                break;
            }
            assertThat(last).isGreaterThanOrEqualTo(1300);
        }
        // Four rounds end in a final offer, here above the combined floor; it never goes below it.
        assertThat(last).isBetween(1300L, created.currentOffer() - 1);

        assertThatThrownBy(() -> negotiations.create(stay(lodge, 2).id(),
                List.of(lodge.twin().id(), lodge.family().id())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("book a single room");
    }

    @Test
    void acceptHoldsBothRoomsAndTheBookingKeepsBothUntilItEnds() {
        Lodge lodge = lodge(4);
        UUID session = openGroup(lodge);
        long price = conversations.accept(session.toString()).offer();
        assertThat(activeHolds(lodge)).isEqualTo(2);
        assertThat(available(lodge)).isEqualTo(2);

        StayRequest stay = stayRequests.find(jdbc.sql("SELECT stay_request_id FROM negotiation_session WHERE id = :id")
                .param("id", session).query(UUID.class).single()).orElseThrow();
        BookingCreated created = fixtures.book(new TestBookings.Agreed(lodge.hotel(), lodge.twin(), stay, session,
                price));
        assertThat(activeHolds(lodge)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM room_hold WHERE booking_id = :b AND status = 'CONVERTED'")
                .param("b", created.bookingId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(available(lodge)).isEqualTo(2);
        assertThat(bookingRepository.rooms(created.bookingId()).describe()).isEqualTo("2 rooms (Twin + Family)");
        assertThat(views.view(created.bookingId()).roomType()).isEqualTo("2 rooms (Twin + Family)");

        fixtures.pay(created);
        // Until booking_request_rooms is approved the owner gets booking_request (number of guests only).
        assertThat(jdbc.sql("SELECT template FROM whatsapp_message WHERE booking_id = :b AND direction = 'OUT'")
                .param("b", created.bookingId()).query(String.class).list()).containsExactly("booking_request");

        bookings.cancel(created.bookingId());
        assertThat(available(lodge)).isEqualTo(4);
        String cancelled = jdbc.sql("""
                        SELECT payload::text FROM whatsapp_message WHERE booking_id = :b
                          AND template = 'booking_cancelled'""")
                .param("b", created.bookingId()).query(String.class).single();
        assertThat(cancelled).contains("the guest cancelled, 2 rooms");
    }

    @Test
    void withOnlyOneRoomLeftTheGroupGetsNeither() {
        Lodge lodge = lodge(2);
        UUID session = openGroup(lodge);
        hotels.roomsFree(lodge.hotel(), TestBookings.NIGHT, 1);
        assertThatThrownBy(() -> conversations.accept(session.toString())).isInstanceOf(RoomGoneException.class);
        assertThat(activeHolds(lodge)).isZero();
        assertThat(available(lodge)).isOne();
    }

    List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoGroupsRacingForThreeRoomsOneGetsBothRoomsAndTheOtherNone() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Lodge lodge = lodge(3);
            UUID a = openGroup(lodge);
            UUID b = openGroup(lodge);
            List<Object> results = race(List.of(() -> conversations.accept(a.toString()),
                    () -> conversations.accept(b.toString())));

            assertThat(results).filteredOn(r -> r instanceof RoomGoneException).hasSize(1);
            assertThat(activeHolds(lodge)).isEqualTo(2);
            assertThat(available(lodge)).isOne();
            for (UUID session : List.of(a, b)) {
                int held = jdbc.sql("SELECT count(*) FROM room_hold WHERE session_id = :s AND status = 'ACTIVE'")
                        .param("s", session).query(Integer.class).single();
                assertThat(held).isIn(0, 2);
            }
        }
    }

    @Test
    void aSingleTravellerAndAGroupRacingForTwoRoomsNeverOversell() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Lodge lodge = lodge(2);
            UUID group = openGroup(lodge);
            UUID single = negotiations.create(stay(lodge, 2).id(), lodge.twin().id()).id();
            List<Object> results = race(List.of(() -> conversations.accept(group.toString()),
                    () -> conversations.accept(single.toString())));

            int groupHeld = jdbc.sql("SELECT count(*) FROM room_hold WHERE session_id = :s AND status = 'ACTIVE'")
                    .param("s", group).query(Integer.class).single();
            int singleHeld = jdbc.sql("SELECT count(*) FROM room_hold WHERE session_id = :s AND status = 'ACTIVE'")
                    .param("s", single).query(Integer.class).single();
            assertThat(groupHeld).isIn(0, 2);
            assertThat(groupHeld + singleHeld).isLessThanOrEqualTo(2);
            assertThat(available(lodge)).isEqualTo(2 - groupHeld - singleHeld).isGreaterThanOrEqualTo(0);
            // Whoever locked first got their rooms; the group can lose to the single (one room left), never half.
            assertThat(results).filteredOn(r -> !(r instanceof RuntimeException)).isNotEmpty();
        }
    }
}
