package com.latestay.conversation;

import com.latestay.conversation.intent.Intent;
import com.latestay.negotiation.EngineDecision;
import com.latestay.negotiation.NegotiationSession;
import java.util.List;
import java.util.Optional;

/** Where conversations live. The production implementation is the database; tests use memory. */
public interface ConversationRepository {

    /**
     * Loads a conversation and holds it exclusively until the surrounding transaction ends.
     *
     * @return empty if there is no conversation with this id
     */
    Optional<Conversation> lockAndLoad(String id);

    /** Stores the session's current state and appends the turns, in order. */
    void save(NegotiationSession session, List<NewTurn> turns);

    /**
     * @param transcript the most recent messages, oldest first (at least the last four)
     */
    record Conversation(NegotiationSession session, List<ChatMessage> transcript, HotelFacts facts) {

        public Conversation {
            transcript = List.copyOf(transcript);
        }
    }

    /**
     * A turn to persist. Traveller turns carry the parsed intent; bot turns carry the engine's decision (null for
     * questions, which don't consult the engine), how the reply was produced and the processing time.
     */
    record NewTurn(ChatMessage.Sender role, String text, Intent intent, EngineDecision decision,
            String validatorResult, Integer latencyMs) {

        public static NewTurn traveller(String text, Intent intent) {
            return new NewTurn(ChatMessage.Sender.TRAVELLER, text, intent, null, null, null);
        }

        public static NewTurn bot(String text, EngineDecision decision, String validatorResult, int latencyMs) {
            return new NewTurn(ChatMessage.Sender.HOTEL, text, null, decision, validatorResult, latencyMs);
        }
    }
}
