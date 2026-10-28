package com.latestay.conversation;

import com.latestay.negotiation.NegotiationSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory negotiation sessions and their chat transcripts. Lost on restart. */
@Component
public class ConversationStore {

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public Conversation save(NegotiationSession session) {
        Conversation conversation = new Conversation(session);
        conversations.put(session.id(), conversation);
        return conversation;
    }

    public Optional<Conversation> find(String id) {
        return Optional.ofNullable(conversations.get(id));
    }

    /** One session plus its transcript. Callers synchronize on this object while handling a turn. */
    public static final class Conversation {

        private final NegotiationSession session;
        private final List<ChatMessage> transcript = new ArrayList<>();

        Conversation(NegotiationSession session) {
            this.session = session;
        }

        public NegotiationSession session() {
            return session;
        }

        public synchronized List<ChatMessage> transcript() {
            return List.copyOf(transcript);
        }

        public synchronized void append(ChatMessage message) {
            transcript.add(message);
        }
    }
}
