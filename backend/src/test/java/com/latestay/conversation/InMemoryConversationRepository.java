package com.latestay.conversation;

import com.latestay.negotiation.NegotiationSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test double: keeps live sessions and every saved turn in memory. */
public final class InMemoryConversationRepository implements ConversationRepository {

    private record Stored(NegotiationSession session, HotelFacts facts, List<NewTurn> turns) {
    }

    private final Map<String, Stored> conversations = new ConcurrentHashMap<>();

    public void put(NegotiationSession session, HotelFacts facts) {
        conversations.put(session.id(), new Stored(session, facts, new ArrayList<>()));
    }

    @Override
    public Optional<Conversation> lockAndLoad(String id) {
        return Optional.ofNullable(conversations.get(id)).map(s -> new Conversation(s.session(),
                s.turns().stream().map(t -> new ChatMessage(t.role(), t.text())).toList(), s.facts()));
    }

    @Override
    public void save(NegotiationSession session, List<NewTurn> turns) {
        conversations.get(session.id()).turns().addAll(turns);
    }

    public List<NewTurn> turns(String id) {
        return List.copyOf(conversations.get(id).turns());
    }
}
