package com.latestay.conversation.reply;

import com.latestay.conversation.ChatMessage;
import com.latestay.conversation.HotelFacts;
import com.latestay.conversation.intent.Register;
import com.latestay.negotiation.EngineDecision;
import java.util.List;

/**
 * Everything a reply may draw on.
 *
 * @param decision the engine's decision; its amount is the only price the reply may mention
 * @param question set when the traveller asked about the hotel; the price did not move
 * @param recentMessages up to the last four messages, oldest first, ending with the traveller's
 */
public record ReplyContext(EngineDecision decision, HotelFacts facts, Register register,
        List<ChatMessage> recentMessages, String question) {

    public ReplyContext {
        recentMessages = List.copyOf(recentMessages);
    }

    public long amount() {
        return decision.amount();
    }

    public boolean isQuestion() {
        return question != null;
    }
}
