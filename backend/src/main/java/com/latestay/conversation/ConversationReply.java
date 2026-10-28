package com.latestay.conversation;

import io.swagger.v3.oas.annotations.media.Schema;
import com.latestay.conversation.intent.IntentType;
import com.latestay.conversation.llm.LlmUsage;
import com.latestay.negotiation.ReasonCode;
import com.latestay.negotiation.SessionStatus;

/**
 * @param offer the price on the table after this message (the agreed price once AGREED)
 * @param reasonCode the engine's reason; null for questions, which don't move the engine
 * @param replySource whether the reply is a validated LLM draft or a fixed template
 * @param llmUsage LLM calls and tokens spent on this message (intent parsing and phrasing)
 */
public record ConversationReply(String reply, long offer, SessionStatus status, @Schema(nullable = true) ReasonCode reasonCode,
        IntentType intent, ReplySource replySource, LlmUsage llmUsage) {

    public enum ReplySource {
        LLM,
        TEMPLATE
    }
}
