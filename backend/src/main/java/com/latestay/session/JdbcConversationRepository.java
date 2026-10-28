package com.latestay.session;

import com.latestay.conversation.ChatMessage;
import com.latestay.conversation.ConversationRepository;
import com.latestay.conversation.HotelFacts;
import com.latestay.conversation.intent.Intent;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.Json;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.negotiation.SessionSnapshot;
import com.latestay.negotiation.SessionStatus;
import com.latestay.negotiation.SlotType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Negotiation sessions and turns in Postgres. Sessions are rebuilt through {@link NegotiationEngine#restore}; the
 * engine itself never touches the database.
 */
@Repository
public class JdbcConversationRepository implements ConversationRepository {

    static final int TRANSCRIPT_TURNS = 8;

    private final JdbcClient jdbc;
    private final NegotiationEngine engine;

    public JdbcConversationRepository(JdbcClient jdbc, NegotiationEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    /** Inserts a newly opened session and its opening bot turn. */
    public void create(NegotiationSession session, UUID stayRequestId, UUID roomTypeId, NewTurn opening) {
        SessionSnapshot s = session.snapshot();
        PricingPolicy policy = s.policy();
        jdbc.sql("""
                        INSERT INTO negotiation_session (id, stay_request_id, room_type_id, status, current_offer,
                               agreed_price, round, list_price, floor_price, slot_type, max_rounds, round_to,
                               arrival_time, expires_at, created_at, updated_at)
                        VALUES (:id, :stayRequest, :roomType, :status, :offer, :agreed, :round, :list, :floor, :slot,
                                :maxRounds, :roundTo, :arrival, :expires, :created, :created)""")
                .param("id", UUID.fromString(s.id()))
                .param("stayRequest", stayRequestId)
                .param("roomType", roomTypeId)
                .param("status", s.status().name())
                .param("offer", s.currentOffer())
                .param("agreed", s.agreedPrice())
                .param("round", s.round())
                .param("list", policy.listPrice())
                .param("floor", policy.floorPrice())
                .param("slot", policy.slotType().name())
                .param("maxRounds", policy.maxRounds())
                .param("roundTo", policy.roundTo())
                .param("arrival", s.arrival())
                .param("expires", s.expiresAt().atOffset(java.time.ZoneOffset.UTC))
                .param("created", s.createdAt().atOffset(java.time.ZoneOffset.UTC))
                .update();
        insertTurns(s.id(), List.of(opening));
    }

    /** Read-only view for the traveller; no lock. */
    public Optional<NegotiationView> view(UUID id) {
        Optional<NegotiationView> view = jdbc.sql("""
                        SELECT s.id,
                               CASE WHEN s.status IN ('OPEN', 'FINAL_OFFER') AND s.expires_at < now()
                                    THEN 'EXPIRED' ELSE s.status END AS status,
                               s.current_offer, s.agreed_price, s.round, s.max_rounds, s.expires_at,
                               s.stay_request_id, h.id AS hotel_id, h.slug, h.name AS hotel_name, h.town,
                               (SELECT url FROM hotel_photo p WHERE p.hotel_id = h.id ORDER BY sort_order LIMIT 1)
                                   AS photo,
                               r.id AS room_id, r.name AS room_name
                        FROM negotiation_session s
                        JOIN room_type r ON r.id = s.room_type_id
                        JOIN hotel h ON h.id = r.hotel_id
                        WHERE s.id = :id""")
                .param("id", id)
                .query((rs, n) -> new NegotiationView(
                        rs.getObject("id", UUID.class),
                        SessionStatus.valueOf(rs.getString("status")),
                        rs.getLong("current_offer"),
                        rs.getObject("agreed_price") == null ? null : rs.getLong("agreed_price"),
                        rs.getInt("round"),
                        rs.getInt("max_rounds"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("stay_request_id", UUID.class),
                        new NegotiationView.HotelSummary(rs.getObject("hotel_id", UUID.class), rs.getString("slug"),
                                rs.getString("hotel_name"), rs.getString("town"), rs.getString("photo")),
                        new NegotiationView.RoomSummary(rs.getObject("room_id", UUID.class), rs.getString("room_name")),
                        List.of()))
                .optional();
        return view.map(v -> new NegotiationView(v.id(), v.status(), v.currentOffer(), v.agreedPrice(), v.round(),
                v.maxRounds(), v.expiresAt(), v.stayRequestId(), v.hotel(), v.roomType(),
                jdbc.sql("SELECT role, text FROM negotiation_turn WHERE session_id = :id ORDER BY seq")
                        .param("id", id)
                        .query((rs, n) -> new NegotiationView.Message(rs.getString("role"), rs.getString("text")))
                        .list()));
    }

    @Override
    public Optional<Conversation> lockAndLoad(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT s.*, h.name AS hotel_name, h.town AS hotel_town, h.amenities AS hotel_amenities,
                               h.policies::text AS hotel_policies
                        FROM negotiation_session s
                        JOIN room_type r ON r.id = s.room_type_id
                        JOIN hotel h ON h.id = r.hotel_id
                        WHERE s.id = :id
                        FOR UPDATE OF s""")
                .param("id", uuid)
                .query(this::mapConversation)
                .optional()
                .map(c -> new Conversation(c.session(), transcript(uuid), c.facts()));
    }

    @Override
    public void save(NegotiationSession session, List<NewTurn> turns) {
        SessionSnapshot s = session.snapshot();
        jdbc.sql("""
                        UPDATE negotiation_session
                        SET status = :status, current_offer = :offer, agreed_price = :agreed, round = :round,
                            updated_at = now()
                        WHERE id = :id""")
                .param("id", UUID.fromString(s.id()))
                .param("status", s.status().name())
                .param("offer", s.currentOffer())
                .param("agreed", s.agreedPrice())
                .param("round", s.round())
                .update();
        insertTurns(s.id(), turns);
    }

    private void insertTurns(String sessionId, List<NewTurn> turns) {
        if (turns.isEmpty()) {
            return;
        }
        UUID id = UUID.fromString(sessionId);
        int seq = jdbc.sql("SELECT COALESCE(MAX(seq), -1) + 1 FROM negotiation_turn WHERE session_id = :id")
                .param("id", id).query(Integer.class).single();
        for (NewTurn turn : turns) {
            Map<String, Object> params = new HashMap<>();
            params.put("session", id);
            params.put("seq", seq++);
            params.put("role", turn.role() == ChatMessage.Sender.TRAVELLER ? "TRAVELLER" : "BOT");
            params.put("text", turn.text());
            params.put("intent", turn.intent() == null ? null : Json.write(intentJson(turn.intent())));
            params.put("decision", turn.decision() == null ? null : Json.write(turn.decision()));
            params.put("validator", turn.validatorResult());
            params.put("latency", turn.latencyMs());
            jdbc.sql("""
                            INSERT INTO negotiation_turn (session_id, seq, role, text, intent, decision,
                                                          validator_result, latency_ms)
                            VALUES (:session, :seq, :role, :text, CAST(:intent AS jsonb), CAST(:decision AS jsonb),
                                    :validator, :latency)""")
                    .params(params).update();
        }
    }

    private static Map<String, Object> intentJson(Intent intent) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", intent.type().name());
        json.put("amount", intent.amount());
        json.put("question", intent.question());
        json.put("register", intent.register().name().toLowerCase(java.util.Locale.ROOT));
        return json;
    }

    private List<ChatMessage> transcript(UUID sessionId) {
        List<ChatMessage> latestFirst = jdbc.sql("""
                        SELECT role, text FROM negotiation_turn WHERE session_id = :id ORDER BY seq DESC LIMIT :limit""")
                .param("id", sessionId).param("limit", TRANSCRIPT_TURNS)
                .query((rs, n) -> new ChatMessage(
                        "TRAVELLER".equals(rs.getString("role")) ? ChatMessage.Sender.TRAVELLER : ChatMessage.Sender.HOTEL,
                        rs.getString("text")))
                .list();
        List<ChatMessage> ordered = new ArrayList<>(latestFirst);
        Collections.reverse(ordered);
        return ordered;
    }

    private Conversation mapConversation(ResultSet rs, int row) throws SQLException {
        PricingPolicy policy = new PricingPolicy(rs.getLong("list_price"), rs.getLong("floor_price"),
                SlotType.valueOf(rs.getString("slot_type")), rs.getInt("max_rounds"), rs.getInt("round_to"));
        Long agreed = rs.getObject("agreed_price") == null ? null : rs.getLong("agreed_price");
        SessionSnapshot snapshot = new SessionSnapshot(
                rs.getObject("id", UUID.class).toString(),
                policy,
                rs.getObject("arrival_time", java.time.LocalTime.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getLong("current_offer"),
                rs.getInt("round"),
                SessionStatus.valueOf(rs.getString("status")),
                agreed);
        Array amenities = rs.getArray("hotel_amenities");
        HotelFacts facts = HotelFactsMapper.facts(rs.getString("hotel_name"), rs.getString("hotel_town"),
                amenities == null ? List.of() : Arrays.asList((String[]) amenities.getArray()),
                HotelPolicies.fromJson(Json.readMap(rs.getString("hotel_policies"))));
        return new Conversation(engine.restore(snapshot), List.of(), facts);
    }
}
