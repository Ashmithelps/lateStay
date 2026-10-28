package com.latestay.conversation;

import com.latestay.conversation.ConversationStore.Conversation;
import com.latestay.conversation.intent.Intent;
import com.latestay.conversation.intent.IntentParser;
import com.latestay.conversation.intent.IntentType;
import com.latestay.conversation.ConversationReply.ReplySource;
import com.latestay.conversation.llm.LlmUnavailableException;
import com.latestay.conversation.llm.LlmUsageMeter;
import com.latestay.conversation.reply.ReplyContext;
import com.latestay.conversation.reply.ReplyPhraser;
import com.latestay.conversation.reply.ReplyTemplates;
import com.latestay.conversation.reply.ReplyValidator;
import com.latestay.negotiation.EngineAction;
import com.latestay.negotiation.EngineDecision;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.ReasonCode;
import com.latestay.negotiation.TravellerEvent;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * One traveller message in, one front-desk reply out. Prices come only from {@link NegotiationEngine}; the LLM
 * labels the message and words the reply, and every reply passes {@link ReplyValidator} or is replaced by a
 * template. Local profile only until hotels are stored.
 */
@Service
@Profile("local")
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    static final int RECENT_MESSAGES = 4;
    static final int PHRASING_ATTEMPTS = 2;

    private final ConversationStore store;
    private final NegotiationEngine engine;
    private final IntentParser intents;
    private final ReplyPhraser phraser;
    private final ReplyValidator validator;
    private final ReplyTemplates templates;
    private final HotelFactsProvider hotelFacts;

    public ConversationService(ConversationStore store, NegotiationEngine engine, IntentParser intents,
            ReplyPhraser phraser, ReplyValidator validator, ReplyTemplates templates, HotelFactsProvider hotelFacts) {
        this.store = store;
        this.engine = engine;
        this.intents = intents;
        this.phraser = phraser;
        this.validator = validator;
        this.templates = templates;
        this.hotelFacts = hotelFacts;
    }

    public ConversationReply handle(String conversationId, String text) {
        Conversation conversation = store.find(conversationId)
                .orElseThrow(() -> new UnknownConversationException(conversationId));

        synchronized (conversation) {
            NegotiationSession session = conversation.session();
            engine.ensureOpen(session);

            String message = PromptText.truncate(text);
            LlmUsageMeter meter = new LlmUsageMeter();
            Intent intent = intents.parse(message, meter);

            EngineDecision decision;
            ReasonCode reasonCode;
            if (intent.type() == IntentType.QUESTION) {
                decision = new EngineDecision(EngineAction.HOLD, session.currentOffer(), ReasonCode.UNCLEAR);
                reasonCode = null;
            } else {
                decision = engine.respond(session, toEvent(intent));
                reasonCode = decision.reasonCode();
            }

            List<ChatMessage> recent = new ArrayList<>(conversation.transcript());
            recent.add(ChatMessage.traveller(message));
            recent = recent.subList(Math.max(0, recent.size() - RECENT_MESSAGES), recent.size());

            ReplyContext context = new ReplyContext(decision, hotelFacts.factsFor(session), intent.register(),
                    recent, intent.type() == IntentType.QUESTION ? intent.question() : null);
            Composed reply = compose(context, meter);

            conversation.append(ChatMessage.traveller(message));
            conversation.append(ChatMessage.hotel(reply.text()));
            return new ConversationReply(reply.text(), session.currentOffer(), session.status(), reasonCode,
                    intent.type(), reply.source(), meter.usage());
        }
    }

    static TravellerEvent toEvent(Intent intent) {
        return switch (intent.type()) {
            case COUNTER -> new TravellerEvent.Counter(intent.amount());
            // "ok 900": a counter at or above the offer agrees at the offer, below it keeps haggling.
            case ACCEPT -> intent.amount() != null ? new TravellerEvent.Counter(intent.amount()) : new TravellerEvent.Accept();
            case ASK_BEST -> new TravellerEvent.AskBestPrice();
            case OTHER -> new TravellerEvent.Other();
            case QUESTION -> throw new IllegalArgumentException("questions don't move the engine");
        };
    }

    private record Composed(String text, ReplySource source) {
    }

    /** LLM draft, one retry if it fails validation, then the template. No retry after the LLM fails. */
    private Composed compose(ReplyContext context, LlmUsageMeter meter) {
        for (int attempt = 0; attempt < PHRASING_ATTEMPTS; attempt++) {
            String draft;
            try {
                draft = phraser.phrase(context, attempt > 0, meter);
            } catch (LlmUnavailableException e) {
                log.debug("Reply falls back to template: {}", e.getMessage());
                break;
            }
            ReplyValidator.Result result = validator.validate(draft, context.amount());
            if (result.valid()) {
                return new Composed(draft, ReplySource.LLM);
            }
            log.warn("Rejected LLM reply ({}), attempt {}", result.problem(), attempt + 1);
        }
        return new Composed(templates.render(context), ReplySource.TEMPLATE);
    }
}
