package com.latestay.owner;

import com.latestay.hotel.AvailabilityRepository;
import com.latestay.hotel.AvailabilitySource;
import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.hotel.RoomType;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.ledger.Ledger;
import com.latestay.ledger.Settlements;
import com.latestay.owner.OwnerViews.Earnings;
import com.latestay.owner.OwnerViews.OwnerFloor;
import com.latestay.owner.OwnerViews.OwnerRoom;
import com.latestay.owner.OwnerViews.OwnerSettlement;
import com.latestay.owner.OwnerViews.OwnerSummary;
import com.latestay.owner.OwnerViews.TonightBooking;
import com.latestay.stay.InvalidStayException;
import com.latestay.stay.LateStayTime;
import com.latestay.stay.StayWindow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The owner page, for the one hotel of the signed-in session. Every query is filtered by that hotel id. */
@Service
public class OwnerService {

    public static final int MAX_ROOMS = 50;

    private final JdbcClient jdbc;
    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final FloorRuleRepository floorRules;
    private final AvailabilityRepository availability;
    private final Ledger ledger;
    private final Settlements settlements;
    private final com.latestay.reliability.Reliability reliability;
    private final com.latestay.review.Reviews reviews;
    private final Clock clock;

    OwnerService(JdbcClient jdbc, HotelRepository hotels, RoomTypeRepository roomTypes,
            FloorRuleRepository floorRules, AvailabilityRepository availability, Ledger ledger,
            Settlements settlements, com.latestay.reliability.Reliability reliability,
            com.latestay.review.Reviews reviews, Clock clock) {
        this.reliability = reliability;
        this.reviews = reviews;
        this.jdbc = jdbc;
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.floorRules = floorRules;
        this.availability = availability;
        this.ledger = ledger;
        this.settlements = settlements;
        this.clock = clock;
    }

    public LocalDate tonight() {
        return StayWindow.nightOf(LocalDateTime.ofInstant(clock.instant(), LateStayTime.ZONE));
    }

    public OwnerSummary summary(OwnerHotel owner) {
        UUID hotelId = owner.hotelId();
        Hotel hotel = hotels.find(hotelId).orElseThrow(() -> new NotFoundException("hotel", hotelId));
        LocalDate night = tonight();
        Integer reported = jdbc.sql("SELECT rooms_free FROM nightly_availability WHERE hotel_id = :h AND night_of = :n")
                .param("h", hotelId).param("n", night).query(Integer.class).optional().orElse(null);
        LocalDate monday = settlements.thisMonday();
        Earnings earnings = new Earnings(monday,
                ledger.earnedSince(hotelId, monday.atStartOfDay(LateStayTime.ZONE).toInstant()),
                ledger.balance(hotelId), settlements.nextPayoutDate());
        List<OwnerSettlement> past = settlements.forHotel(hotelId, 26).stream()
                .map(s -> new OwnerSettlement(s.id(), s.periodStart(), s.periodEnd(), s.amountPaise(), s.status(),
                        s.upiReference(), s.paidAt()))
                .toList();
        List<OwnerRoom> rooms = roomTypes.forHotel(hotelId).stream()
                .map(r -> new OwnerRoom(r.id(), r.name(), r.listPrice(), floorRules.forRoomType(r.id()).stream()
                        .map(f -> new OwnerFloor(f.id(), f.slotType(), f.weekdays(), f.seasonStart(), f.seasonEnd(),
                                f.minHours(), f.floorPrice()))
                        .toList()))
                .toList();
        return new OwnerSummary(hotel.name(), hotel.town(), night, reported,
                availability.available(hotelId, night, clock.instant()), tonightsBookings(hotelId, night), earnings,
                past, rooms, reliability.of(hotelId), reviews.forHotel(hotelId));
    }

    private List<TonightBooking> tonightsBookings(UUID hotelId, LocalDate night) {
        return jdbc.sql("""
                        SELECT id, guest_name, guests, rooms, arrive_at, code, pay_at_desk, status FROM booking
                        WHERE hotel_id = :h AND night_of = :n
                          AND status IN ('DEPOSIT_PAID', 'HOTEL_CONFIRMED', 'CHECKED_IN', 'NO_SHOW', 'COMPLETED',
                                         'DISPUTED')
                        ORDER BY arrive_at, created_at""")
                .param("h", hotelId).param("n", night)
                .query((rs, n) -> {
                    String status = rs.getString("status");
                    String shown = switch (status) {
                        case "DEPOSIT_PAID" -> "AWAITING_CONFIRMATION";
                        case "HOTEL_CONFIRMED" -> "CONFIRMED";
                        default -> status;
                    };
                    return new TonightBooking(rs.getObject("id", UUID.class), rs.getString("guest_name"),
                            rs.getInt("guests"), rs.getInt("rooms"), LocalDateTime.ofInstant(
                                    rs.getObject("arrive_at", OffsetDateTime.class).toInstant(), LateStayTime.ZONE),
                            rs.getString("code"), rs.getLong("pay_at_desk"), shown,
                            status.equals("CHECKED_IN") || status.equals("COMPLETED"));
                })
                .list();
    }

    /** Tonight's rooms free, as the owner's WhatsApp answer would set it. */
    @Transactional
    public void roomsTonight(OwnerHotel owner, int rooms) {
        if (rooms < 0 || rooms > MAX_ROOMS) {
            throw new InvalidStayException("rooms free must be from 0 to " + MAX_ROOMS);
        }
        availability.upsert(owner.hotelId(), tonight(), rooms, AvailabilitySource.OWNER_PAGE);
    }

    /**
     * A new amount for one of this hotel's floor rules (nothing else about the rule changes). Above zero and never
     * above the room's list price. A rule of another hotel is "not found".
     */
    @Transactional
    public OwnerFloor floorPrice(OwnerHotel owner, UUID ruleId, long floorPrice) {
        FloorRule rule = floorRules.find(ruleId).orElseThrow(() -> new NotFoundException("floor rule", ruleId));
        RoomType room = roomTypes.find(rule.roomTypeId())
                .filter(r -> r.hotelId().equals(owner.hotelId()))
                .orElseThrow(() -> new NotFoundException("floor rule", ruleId));
        if (floorPrice <= 0) {
            throw new InvalidStayException("the lowest price must be more than ₹0");
        }
        if (floorPrice > room.listPrice()) {
            throw new InvalidStayException("the lowest price can't be above the room's list price of ₹"
                    + room.listPrice());
        }
        FloorRule updated = new FloorRule(rule.id(), rule.roomTypeId(), rule.slotType(), rule.weekdays(),
                rule.seasonStart(), rule.seasonEnd(), floorPrice, rule.minHours());
        floorRules.update(rule.id(), updated);
        return new OwnerFloor(rule.id(), rule.slotType(), rule.weekdays(), rule.seasonStart(), rule.seasonEnd(),
                rule.minHours(), floorPrice);
    }

    /** One of this hotel's settlements with its lines; another hotel's is "not found". */
    public List<Settlements.Line> settlementLines(OwnerHotel owner, UUID settlementId) {
        settlements.find(settlementId).filter(s -> s.hotelId().equals(owner.hotelId()))
                .orElseThrow(() -> new NotFoundException("settlement", settlementId));
        return settlements.lines(settlementId);
    }
}
