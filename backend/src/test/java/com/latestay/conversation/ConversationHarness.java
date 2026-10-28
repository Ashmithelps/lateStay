package com.latestay.conversation;

import com.latestay.conversation.intent.AmountExtractor;
import com.latestay.conversation.intent.IntentParser;
import com.latestay.conversation.llm.LlmClient;
import com.latestay.conversation.reply.ReplyPhraser;
import com.latestay.conversation.reply.ReplyTemplates;
import com.latestay.conversation.reply.ReplyValidator;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.negotiation.SlotType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** A ConversationService wired by hand around a given LLM. Sessions open at ₹1,000 (list 1400, floor 700). */
final class ConversationHarness {

    static final long LIST = 1400;
    static final long FLOOR = 700;
    static final HotelFacts FACTS = new HotelFacts("Test Lodge", List.of("Room heater", "Free parking"),
            List.of("Check-in with photo ID"));

    final AmountExtractor amounts = new AmountExtractor();
    final ReplyValidator validator = new ReplyValidator(amounts);
    final ConversationStore store = new ConversationStore();
    final NegotiationEngine engine = new NegotiationEngine(
            Clock.fixed(Instant.parse("2026-10-28T17:30:00Z"), ZoneId.of("Asia/Kolkata")));
    final ConversationService service;

    ConversationHarness(LlmClient llm) {
        service = new ConversationService(store, engine, new IntentParser(llm, amounts), new ReplyPhraser(llm),
                validator, new ReplyTemplates(validator), session -> FACTS);
    }

    NegotiationSession open() {
        NegotiationSession session = engine.open(PricingPolicy.of(LIST, FLOOR, SlotType.LATE_NIGHT), LocalTime.of(23, 30));
        store.save(session);
        return session;
    }
}
