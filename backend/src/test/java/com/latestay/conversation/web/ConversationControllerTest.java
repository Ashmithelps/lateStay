package com.latestay.conversation.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.config.NegotiationConfig;
import com.latestay.config.SecurityConfig;
import com.latestay.conversation.ConversationService;
import com.latestay.conversation.HotelFacts;
import com.latestay.conversation.InMemoryConversationRepository;
import com.latestay.conversation.intent.AmountExtractor;
import com.latestay.conversation.intent.IntentParser;
import com.latestay.conversation.llm.LlmClient;
import com.latestay.conversation.llm.NoLlmClient;
import com.latestay.conversation.reply.ReplyPhraser;
import com.latestay.conversation.reply.ReplyTemplates;
import com.latestay.conversation.reply.ReplyValidator;
import com.latestay.negotiation.NegotiationEngine;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.PricingPolicy;
import com.latestay.negotiation.SlotType;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ConversationController.class)
@Import({SecurityConfig.class, NegotiationConfig.class, ConversationService.class, IntentParser.class,
        ReplyPhraser.class, ReplyValidator.class, ReplyTemplates.class, ConversationControllerTest.Beans.class})
class ConversationControllerTest {

    @TestConfiguration
    static class Beans {

        @Bean
        AmountExtractor amountExtractor() {
            return new AmountExtractor();
        }

        @Bean
        LlmClient llmClient() {
            return new NoLlmClient("test");
        }

        @Bean
        InMemoryConversationRepository conversationRepository() {
            return new InMemoryConversationRepository();
        }

        /** Rooms are always free here; holds are tested against the database (RoomHoldIT). */
        @Bean
        com.latestay.conversation.llm.LlmSpend llmSpend(LlmClient llm) {
            return new com.latestay.conversation.llm.LlmSpend(new com.latestay.conversation.llm.LlmProperties("none",
                    null, null, null, java.time.Duration.ofSeconds(8), java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO), llm, null, java.time.Clock.systemUTC());
        }

        @Bean
        com.latestay.conversation.RoomGuard roomGuard() {
            return sessionId -> { };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private NegotiationEngine engine;

    @Autowired
    private InMemoryConversationRepository repository;

    @Test
    void repliesWithEnginePriceAndStatus() throws Exception {
        String id = open();

        send(id, "700 mein de do bhai")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Chaliye beech ka rakhte hain, ₹900 mein ho jayega."))
                .andExpect(jsonPath("$.offer").value(900))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reasonCode").value("MEETING_HALFWAY"))
                .andExpect(jsonPath("$.intent").value("COUNTER"))
                .andExpect(jsonPath("$.replySource").value("TEMPLATE"))
                .andExpect(jsonPath("$.llmUsage.calls").value(2))
                .andExpect(jsonPath("$.llmUsage.inputTokens").value(0));

        send(id, "heater hai?")
                .andExpect(jsonPath("$.reply").value("Haan, room heater hai. Room abhi ₹900 ka hai."))
                .andExpect(jsonPath("$.offer").value(900))
                .andExpect(jsonPath("$.reasonCode").doesNotExist())
                .andExpect(jsonPath("$.intent").value("QUESTION"));
    }

    @Test
    void rejectsBadRequestsUnknownAndClosedSessions() throws Exception {
        String id = open();

        send(id, " ").andExpect(status().isBadRequest());
        send(id, "x".repeat(501)).andExpect(status().isBadRequest());
        send("nope", "hi").andExpect(status().isNotFound());

        send(id, "ok done").andExpect(jsonPath("$.status").value("AGREED"));
        send(id, "500").andExpect(status().isConflict()).andExpect(jsonPath("$.sessionStatus").value("AGREED"));
    }

    private String open() {
        NegotiationSession session = engine.open(PricingPolicy.of(1400, 700, SlotType.LATE_NIGHT), LocalTime.of(23, 30));
        repository.put(session, new HotelFacts("Test Lodge", List.of("Room heater"), List.of("Photo ID")));
        return session.id();
    }

    private ResultActions send(String id, String text) throws Exception {
        return mvc.perform(post("/api/negotiations/{id}/messages", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": " + com.latestay.conversation.PromptText.quote(text) + "}"));
    }
}
