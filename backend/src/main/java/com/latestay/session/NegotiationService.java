package com.latestay.session;

import com.latestay.conversation.ConversationRepository.NewTurn;
import com.latestay.conversation.ConversationService;
import com.latestay.conversation.ConversationService.OpeningReply;
import com.latestay.hotel.AvailabilityRepository;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.hotel.RoomType;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.pricing.PricingInputs;
import com.latestay.reliability.Reliability;
import com.latestay.stay.StayPricing;
import com.latestay.stay.StayRequest;
import com.latestay.stay.StayRequestRepository;
import com.latestay.stay.StayWindow;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens DB-backed negotiations for a stay request and a room type. */
@Service
public class NegotiationService {

    private final StayRequestRepository stayRequests;
    private final RoomTypeRepository roomTypes;
    private final HotelRepository hotels;
    private final FloorRuleRepository floorRules;
    private final AvailabilityRepository availability;
    private final NegotiationEngine engine;
    private final ConversationService conversations;
    private final JdbcConversationRepository sessions;
    private final PricingInputs pricing;
    private final Reliability reliability;
    private final java.time.Clock clock;

    public NegotiationService(StayRequestRepository stayRequests, RoomTypeRepository roomTypes, HotelRepository hotels,
            FloorRuleRepository floorRules, AvailabilityRepository availability, NegotiationEngine engine,
            ConversationService conversations, JdbcConversationRepository sessions, PricingInputs pricing,
            Reliability reliability, java.time.Clock clock) {
        this.clock = clock;
        this.pricing = pricing;
        this.reliability = reliability;
        this.stayRequests = stayRequests;
        this.roomTypes = roomTypes;
        this.hotels = hotels;
        this.floorRules = floorRules;
        this.availability = availability;
        this.engine = engine;
        this.conversations = conversations;
        this.sessions = sessions;
    }

    @Transactional
    public NegotiationCreated create(UUID stayRequestId, UUID roomTypeId) {
        return create(stayRequestId, List.of(roomTypeId));
    }

    /**
     * Opens a negotiation for one room, or for two rooms at the same hotel together (a group no single room fits):
     * list and floor are then the sums of both rooms'.
     */
    @Transactional
    public NegotiationCreated create(UUID stayRequestId, List<UUID> roomTypeIds) {
        if (roomTypeIds.isEmpty() || roomTypeIds.size() > 2) {
            throw new ConflictException("Choose one room, or two for a group");
        }
        StayRequest request = stayRequests.find(stayRequestId)
                .orElseThrow(() -> new NotFoundException("stay request", stayRequestId));
        List<RoomType> rooms = roomTypeIds.stream()
                .map(id -> roomTypes.find(id).orElseThrow(() -> new NotFoundException("room type", id))).toList();
        RoomType first = rooms.getFirst();
        if (rooms.stream().anyMatch(r -> !r.hotelId().equals(first.hotelId()))) {
            throw new ConflictException("Both rooms must be at the same hotel");
        }
        Hotel hotel = hotels.find(first.hotelId()).filter(Hotel::active)
                .orElseThrow(() -> new NotFoundException("room type", first.id()));
        if (reliability.paused(hotel.id())) {
            throw new ConflictException(hotel.name() + " isn't taking bookings right now");
        }
        StayWindow window = request.window();
        int sleeps = rooms.stream().mapToInt(RoomType::maxGuests).sum();
        String name = rooms.stream().map(RoomType::name).collect(Collectors.joining(" + "));
        if (sleeps < request.guests()) {
            throw new ConflictException(name + " sleeps " + sleeps + ", not " + request.guests());
        }
        if (rooms.size() == 2 && singleRoomFits(hotel, window, request.guests())) {
            throw new ConflictException("One room at " + hotel.name() + " sleeps " + request.guests()
                    + ": book a single room");
        }
        int available = availability.available(hotel.id(), window.nightOf(), clock.instant());
        if (available < rooms.size()) {
            throw new ConflictException(hotel.name() + (rooms.size() == 1 ? " has no rooms" : " doesn't have "
                    + rooms.size() + " rooms") + " free for the night of " + window.nightOf());
        }
        var rules = floorRules.forRoomTypes(rooms.stream().map(RoomType::id).distinct().toList(), window.slotType())
                .stream().collect(Collectors.groupingBy(FloorRule::roomTypeId));
        PricingPolicy policy = StayPricing.policy(rooms, rules, window,
                        pricing.context(hotel.town(), window.nightOf(), available, hotel.totalRooms()))
                .orElseThrow(() -> new ConflictException(name + " isn't offered for this stay"));

        NegotiationSession session = engine.open(policy, window.arrive().toLocalTime());
        OpeningReply opening = conversations.openingReply(session,
                HotelFactsMapper.facts(hotel.name(), hotel.town(), hotel.amenities(), hotel.policies()));
        sessions.create(session, request.id(), roomTypeIds, NewTurn.bot(opening.text(),
                session.history().getFirst().decision(), opening.validatorResult(), opening.latencyMs(), opening.spend()));

        return new NegotiationCreated(UUID.fromString(session.id()), session.status(), session.currentOffer(),
                session.round(), policy.maxRounds(), session.expiresAt(),
                new NegotiationCreated.Ref(hotel.id(), hotel.name()),
                new NegotiationCreated.Ref(first.id(), name), rooms.size(),
                List.of(new NegotiationCreated.Message("BOT", opening.text())));
    }

    /** Search offers two rooms only when no single room on offer for the stay fits the guests; so does this. */
    private boolean singleRoomFits(Hotel hotel, StayWindow window, int guests) {
        List<RoomType> singles = roomTypes.fitting(List.of(hotel.id()), guests);
        var rules = floorRules.forRoomTypes(singles.stream().map(RoomType::id).toList(), window.slotType()).stream()
                .collect(Collectors.groupingBy(FloorRule::roomTypeId));
        return singles.stream().anyMatch(r -> StayPricing.policy(List.of(r), rules, window,
                com.latestay.negotiation.PricingContext.LEGACY).isPresent());
    }
}
