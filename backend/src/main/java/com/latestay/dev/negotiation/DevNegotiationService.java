package com.latestay.dev.negotiation;

import com.latestay.conversation.ConversationRepository.NewTurn;
import com.latestay.conversation.ConversationService;
import com.latestay.conversation.ConversationService.OpeningReply;
import com.latestay.conversation.UnknownConversationException;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.RoomType;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.negotiation.EngineDecision;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.negotiation.TravellerEvent;
import com.latestay.session.JdbcConversationRepository;
import com.latestay.stay.LateStayTime;
import com.latestay.stay.StayRequest;
import com.latestay.stay.StayRequestRepository;
import com.latestay.stay.StayWindow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed sessions with prices given by the caller (the simulator), attached to an inactive "Simulator Lodge"
 * that never appears in search. Local profile only.
 */
@Service
@Profile("local")
class DevNegotiationService {

    static final String SIM_SLUG = "simulator-lodge";

    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final StayRequestRepository stayRequests;
    private final JdbcConversationRepository sessions;
    private final ConversationService conversations;
    private final NegotiationEngine engine;
    private final Clock clock;

    DevNegotiationService(HotelRepository hotels, RoomTypeRepository roomTypes, StayRequestRepository stayRequests,
            JdbcConversationRepository sessions, ConversationService conversations, NegotiationEngine engine,
            Clock clock) {
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.stayRequests = stayRequests;
        this.sessions = sessions;
        this.conversations = conversations;
        this.engine = engine;
        this.clock = clock;
    }

    @Transactional
    NegotiationSession create(PricingPolicy policy, LocalTime arrival) {
        Hotel lodge = simulatorLodge();
        RoomType room = roomTypes.forHotel(lodge.id()).getFirst();

        LocalDate today = LocalDate.now(clock.withZone(LateStayTime.ZONE));
        LocalDate arrivalDate = arrival.isBefore(LocalTime.of(6, 0)) ? today.plusDays(1) : today;
        StayWindow window = StayWindow.of(arrivalDate.atTime(arrival), arrivalDate.atTime(arrival).plusHours(1));
        StayRequest request = stayRequests.insert(lodge.lat(), lodge.lng(), window, 1, List.of());

        NegotiationSession session = engine.open(policy, arrival);
        OpeningReply opening = conversations.openingReply(session,
                new com.latestay.conversation.HotelFacts(lodge.name(), List.of("Room heater"), List.of()));
        sessions.create(session, request.id(), room.id(), NewTurn.bot(opening.text(),
                session.history().getFirst().decision(), opening.validatorResult(), opening.latencyMs()));
        return session;
    }

    @Transactional
    TurnResponse turn(String id, TravellerEvent event) {
        var conversation = sessions.lockAndLoad(id).orElseThrow(() -> new UnknownConversationException(id));
        EngineDecision decision = engine.respond(conversation.session(), event);
        sessions.save(conversation.session(), List.of());
        return new TurnResponse(decision, conversation.session().status());
    }

    private Hotel simulatorLodge() {
        return hotels.findBySlug(SIM_SLUG).orElseGet(() -> {
            Hotel lodge = hotels.insert(new Hotel(null, "Simulator Lodge", SIM_SLUG, "Manali", "Not a real hotel",
                    32.2432, 77.1892, "+910000000000", List.of("heater"),
                    new HotelPolicies(false, false, null), BigDecimal.ZERO, false, List.of()));
            roomTypes.insert(lodge.id(), "Simulator room", 1, 10);
            return lodge;
        });
    }
}
