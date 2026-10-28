package com.latestay.session;

import com.latestay.conversation.ConversationRepository.NewTurn;
import com.latestay.conversation.ConversationService;
import com.latestay.conversation.ConversationService.OpeningReply;
import com.latestay.hotel.AvailabilityRepository;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.hotel.FloorRuleResolver;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.hotel.RoomType;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.stay.StayRequest;
import com.latestay.stay.StayRequestRepository;
import com.latestay.stay.StayWindow;
import java.util.List;
import java.util.UUID;
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

    public NegotiationService(StayRequestRepository stayRequests, RoomTypeRepository roomTypes, HotelRepository hotels,
            FloorRuleRepository floorRules, AvailabilityRepository availability, NegotiationEngine engine,
            ConversationService conversations, JdbcConversationRepository sessions) {
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
        StayRequest request = stayRequests.find(stayRequestId)
                .orElseThrow(() -> new NotFoundException("stay request", stayRequestId));
        RoomType room = roomTypes.find(roomTypeId).orElseThrow(() -> new NotFoundException("room type", roomTypeId));
        Hotel hotel = hotels.find(room.hotelId()).filter(Hotel::active)
                .orElseThrow(() -> new NotFoundException("room type", roomTypeId));
        StayWindow window = request.window();

        if (room.maxGuests() < request.guests()) {
            throw new ConflictException(room.name() + " sleeps " + room.maxGuests() + ", not " + request.guests());
        }
        if (availability.roomsFree(hotel.id(), window.nightOf()) <= 0) {
            throw new ConflictException(hotel.name() + " has no rooms free for the night of " + window.nightOf());
        }
        FloorRule rule = FloorRuleResolver.resolve(floorRules.forRoomType(room.id()), window.slotType(),
                        window.nightOf(), window.hours())
                .orElseThrow(() -> new ConflictException(room.name() + " isn't offered for this stay"));

        PricingPolicy policy = PricingPolicy.of(room.listPrice(), rule.floorPrice(), window.slotType());
        NegotiationSession session = engine.open(policy, window.arrive().toLocalTime());
        OpeningReply opening = conversations.openingReply(session,
                HotelFactsMapper.facts(hotel.name(), hotel.town(), hotel.amenities(), hotel.policies()));
        sessions.create(session, request.id(), room.id(), NewTurn.bot(opening.text(),
                session.history().getFirst().decision(), opening.validatorResult(), opening.latencyMs()));

        return new NegotiationCreated(UUID.fromString(session.id()), session.status(), session.currentOffer(),
                session.round(), policy.maxRounds(), session.expiresAt(),
                new NegotiationCreated.Ref(hotel.id(), hotel.name()),
                new NegotiationCreated.Ref(room.id(), room.name()),
                List.of(new NegotiationCreated.Message("BOT", opening.text())));
    }
}
