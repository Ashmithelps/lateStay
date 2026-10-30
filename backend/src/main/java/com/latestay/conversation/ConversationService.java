package com.latestay.conversation;

import com.latestay.conversation.ConversationReply.ReplySource;
import com.latestay.conversation.ConversationRepository.Conversation;
import com.latestay.conversation.ConversationRepository.NewTurn;
import com.latestay.conversation.intent.Intent;
import com.latestay.conversation.intent.IntentParser;
import com.latestay.conversation.intent.IntentType;
import com.latestay.conversation.intent.Register;
import com.latestay.conversation.llm.LlmUnavailableException;
import com.latestay.conversation.llm.LlmUsageMeter;
import com.latestay.conversation.reply.Money;
import com.latestay.conversation.reply.ReplyContext;
import com.latestay.conversation.reply.ReplyPhraser;
import com.latestay.conversation.reply.ReplyTemplates;
import com.latestay.conversation.reply.ReplyValidator;
import com.latestay.negotiation.EngineAction;
import com.latestay.negotiation.EngineDecision;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.ReasonCode;
import com.latestay.negotiation.SessionClosedException;
import com.latestay.negotiation.TravellerEvent;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One traveller message in, one front-desk reply out. Prices come only from {@link NegotiationEngine}; the LLM
 * labels the message and words the reply, and every reply passes {@link ReplyValidator} or is replaced by a
 * template. Both turns are persisted with the session's new state in one transaction.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    static final int RECENT_MESSAGES = 4;
    static final int PHRASING_ATTEMPTS = 2;
    public static final String LLM_OK = "LLM_OK";

    private final ConversationRepository conversations;
    private final NegotiationEngine engine;
    private final IntentParser intents;
    private final ReplyPhraser phraser;
    private final ReplyValidator validator;
    private final ReplyTemplates templates;
    private final RoomGuard rooms;

    private final com.latestay.conversation.llm.LlmSpend spend;

    public ConversationService(ConversationRepository conversations, NegotiationEngine engine, IntentParser intents,
            ReplyPhraser phraser, ReplyValidator validator, ReplyTemplates templates, RoomGuard rooms,
            com.latestay.conversation.llm.LlmSpend spend) {
        this.spend = spend;
        this.rooms = rooms;
        this.conversations = conversations;
        this.engine = engine;
        this.intents = intents;
        this.phraser = phraser;
        this.validator = validator;
        this.templates = templates;
    }

    /** The bot's first message for a freshly opened session. */
    public record OpeningReply(String text, ReplySource source, String validatorResult, int latencyMs,
            com.latestay.conversation.llm.LlmSpend.TurnSpend spend) {
    }

    public OpeningReply openingReply(NegotiationSession session, HotelFacts facts) {
        long started = System.nanoTime();
        EngineDecision opening = session.history().getFirst().decision();
        LlmUsageMeter meter = new LlmUsageMeter();
        Composed reply = compose(new ReplyContext(opening, facts, Register.ENGLISH, List.of(), null), meter);
        return new OpeningReply(reply.text(), reply.source(), reply.validation(), elapsedMs(started),
                spend.of(meter.usage()));
    }

    @Transactional(noRollbackFor = SessionClosedException.class)
    public ConversationReply handle(String conversationId, String text) {
        long started = System.nanoTime();
        Conversation conversation = conversations.lockAndLoad(conversationId)
                .orElseThrow(() -> new UnknownConversationException(conversationId));
        NegotiationSession session = conversation.session();
        try {
            engine.ensureOpen(session);
        } catch (SessionClosedException e) {
            conversations.save(session, List.of()); // keep an EXPIRED status that was just set
            throw e;
        }

        String message = PromptText.truncate(text);
        LlmUsageMeter meter = new LlmUsageMeter();
        Intent intent = intents.parse(message, meter);

        EngineDecision decision;
        ReasonCode reasonCode;
        boolean question = intent.type() == IntentType.QUESTION;
        if (question) {
            decision = new EngineDecision(EngineAction.HOLD, session.currentOffer(), ReasonCode.UNCLEAR);
            reasonCode = null;
        } else {
            decision = engine.respond(session, toEvent(intent));
            reasonCode = decision.reasonCode();
            reserveRoomIfAgreed(conversationId, decision);
        }

        List<ChatMessage> recent = new ArrayList<>(conversation.transcript());
        recent.add(ChatMessage.traveller(message));
        recent = recent.subList(Math.max(0, recent.size() - RECENT_MESSAGES), recent.size());

        ReplyContext context = new ReplyContext(decision, conversation.facts(), intent.register(), recent,
                question ? intent.question() : null);
        Composed reply = compose(context, meter);

        conversations.save(session, List.of(
                NewTurn.traveller(message, intent),
                NewTurn.bot(reply.text(), question ? null : decision, reply.validation(), elapsedMs(started),
                        spend.of(meter.usage()))));
        return new ConversationReply(reply.text(), session.currentOffer(), session.status(), reasonCode,
                intent.type(), reply.source(), meter.usage());
    }

    /**
     * The traveller pressed Accept: an explicit engine {@code Accept}, no intent parsing. Agrees at the current
     * offer (or replies with the template if the session can't agree).
     */
    @Transactional(noRollbackFor = SessionClosedException.class)
    public ConversationReply accept(String conversationId) {
        long started = System.nanoTime();
        Conversation conversation = conversations.lockAndLoad(conversationId)
                .orElseThrow(() -> new UnknownConversationException(conversationId));
        NegotiationSession session = conversation.session();
        try {
            engine.ensureOpen(session);
        } catch (SessionClosedException e) {
            conversations.save(session, List.of());
            throw e;
        }

        String message = "Accept " + Money.format(session.currentOffer());
        Intent intent = new Intent(IntentType.ACCEPT, null, null, lastRegister(conversation));
        EngineDecision decision = engine.respond(session, new TravellerEvent.Accept());
        reserveRoomIfAgreed(conversationId, decision);

        List<ChatMessage> recent = new ArrayList<>(conversation.transcript());
        recent.add(ChatMessage.traveller(message));
        recent = recent.subList(Math.max(0, recent.size() - RECENT_MESSAGES), recent.size());
        LlmUsageMeter meter = new LlmUsageMeter();
        Composed reply = compose(new ReplyContext(decision, conversation.facts(), intent.register(), recent, null), meter);

        conversations.save(session, List.of(
                NewTurn.traveller(message, intent),
                NewTurn.bot(reply.text(), decision, reply.validation(), elapsedMs(started),
                        spend.of(meter.usage()))));
        return new ConversationReply(reply.text(), session.currentOffer(), session.status(), decision.reasonCode(),
                IntentType.ACCEPT, reply.source(), meter.usage());
    }

    /**
     * An agreement needs a room: take the hold now, inside this transaction. If the last room just went, the
     * exception rolls everything back, so the session is not agreed.
     */
    private void reserveRoomIfAgreed(String conversationId, EngineDecision decision) {
        if (decision.action() == EngineAction.AGREE) {
            rooms.reserve(conversationId);
        }
    }

    /** Hinglish if the traveller's last message was; otherwise English. */
    private Register lastRegister(Conversation conversation) {
        return conversation.transcript().reversed().stream()
                .filter(m -> m.from() == ChatMessage.Sender.TRAVELLER)
                .findFirst()
                .map(m -> new com.latestay.conversation.intent.KeywordIntentClassifier(
                        new com.latestay.conversation.intent.AmountExtractor()).register(m.text()))
                .orElse(Register.ENGLISH);
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

    private record Composed(String text, ReplySource source, String validation) {
    }

    /** LLM draft, one retry if it fails validation, then the template. No retry after the LLM fails. */
    private Composed compose(ReplyContext context, LlmUsageMeter meter) {
        List<String> problems = new ArrayList<>();
        for (int attempt = 0; attempt < PHRASING_ATTEMPTS; attempt++) {
            String draft;
            try {
                draft = phraser.phrase(context, attempt > 0, meter);
            } catch (LlmUnavailableException e) {
                log.debug("Reply falls back to template: {}", e.getMessage());
                problems.add(e.timedOut() ? "llm timed out" : "llm unavailable");
                break;
            }
            ReplyValidator.Result result = validator.validate(draft, context.amount());
            if (result.valid()) {
                return new Composed(draft, ReplySource.LLM, LLM_OK);
            }
            log.warn("Rejected LLM reply ({}), attempt {}", result.problem(), attempt + 1);
            problems.add(result.problem());
        }
        return new Composed(templates.render(context), ReplySource.TEMPLATE, "TEMPLATE: " + String.join("; ", problems));
    }

    private static int elapsedMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }
}
