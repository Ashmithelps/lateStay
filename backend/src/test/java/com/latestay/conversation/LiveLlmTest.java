package com.latestay.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.latestay.conversation.intent.AmountExtractor;
import com.latestay.conversation.intent.Intent;
import com.latestay.conversation.intent.IntentParser;
import com.latestay.conversation.intent.IntentType;
import com.latestay.conversation.intent.Register;
import com.latestay.conversation.llm.LlmClient;
import com.latestay.conversation.llm.LlmConfig;
import com.latestay.conversation.llm.LlmProperties;
import com.latestay.conversation.llm.NoLlmClient;
import com.latestay.negotiation.NegotiationSession;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

/**
 * Calls the real model configured by LLM_PROVIDER / LLM_MODEL / LLM_API_KEY. Excluded from {@code ./gradlew test};
 * run with {@code ./gradlew llmTest}. Skipped when no LLM is configured.
 */
@Tag("llm")
class LiveLlmTest {

    private static LlmClient llm;

    @BeforeAll
    static void connect() {
        llm = LlmConfig.create(new LlmProperties(System.getenv("LLM_PROVIDER"), System.getenv("LLM_MODEL"),
                System.getenv("LLM_API_KEY"), System.getenv("LLM_BASE_URL"), Duration.ofSeconds(8), java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO), RestClient.builder());
    }

    private static void requireLlm() {
        assumeFalse(llm instanceof NoLlmClient, "no LLM configured");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "700 mein de do bhai           | COUNTER  | 700  | HINGLISH",
            "saat sau final?               | COUNTER  | 700  | HINGLISH",
            "heater hai?                   | QUESTION |      | HINGLISH",
            "Can you do seven hundred?     | COUNTER  | 700  | ENGLISH",
            "ok fine, book it              | ACCEPT   |      | ENGLISH",
            "what's your best price?       | ASK_BEST |      | ENGLISH",
            "satt sau te de do paaji       | COUNTER  | 700  | HINGLISH",
    })
    void parsesExamplePhrases(String text, IntentType type, Long amount, Register register) {
        requireLlm();
        Intent intent = new IntentParser(llm, new AmountExtractor()).parse(text);

        assertThat(intent.type()).isEqualTo(type);
        assertThat(intent.amount()).isEqualTo(amount);
        assertThat(intent.register()).isEqualTo(register);
    }

    @Test
    void realRepliesPassValidationForAHaggle() {
        requireLlm();
        ConversationHarness h = new ConversationHarness(llm);
        NegotiationSession session = h.open();

        for (String message : new String[] {"500 mein do na bhai", "heater hai?", "700 final?", "theek hai"}) {
            ConversationReply reply = h.service.handle(session.id(), message);
            assertThat(h.amounts.extractDistinct(reply.reply())).as(reply.reply()).containsExactly(reply.offer());
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("com.latestay.conversation.AdversarialMessagesTest#messages")
    void adversarialMessagesAgainstRealModel(String message) {
        requireLlm();
        ConversationHarness h = new ConversationHarness(llm);
        NegotiationSession session = h.open();

        AdversarialMessagesTest.assertSafe(h, session, h.service.handle(session.id(), message), message);
    }
}
