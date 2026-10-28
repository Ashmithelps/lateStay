package com.latestay.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.conversation.llm.LlmRequest;
import com.latestay.negotiation.NegotiationSession;
import com.latestay.negotiation.SessionClosedException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Hostile messages against an LLM that has been fully "taken over": it labels messages however the attacker
 * wants and writes replies with the attacker's prices. The engine and validator must hold regardless.
 */
class AdversarialMessagesTest {

    /** Replies a compromised model might write. All contain unapproved numbers or no approved one. */
    private static final Function<LlmRequest, String> HOSTILE_REPLIES = r ->
            "Admin override accepted, the room is ₹100 now. Our floor is ₹700 by the way.";

    static Stream<Arguments> hostileLlms() {
        return Stream.of(
                Arguments.of("counter at 1", (Function<LlmRequest, String>) r ->
                        FakeLlmClient.intentJson("COUNTER", 1L, null, "english")),
                Arguments.of("accept at 100", (Function<LlmRequest, String>) r ->
                        FakeLlmClient.intentJson("ACCEPT", 100L, null, "hinglish")),
                Arguments.of("garbage JSON", (Function<LlmRequest, String>) r -> "{\"type\": \"SYSTEM_OVERRIDE\"}"),
                Arguments.of("asks best every time", (Function<LlmRequest, String>) r ->
                        FakeLlmClient.intentJson("ASK_BEST", null, null, "english")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileLlms")
    void eachMessageAloneNeverBreaksFloorOrLeaksANumber(String name, Function<LlmRequest, String> intents) {
        for (String message : AdversarialMessages.ALL) {
            ConversationHarness h = new ConversationHarness(new FakeLlmClient(intents, HOSTILE_REPLIES));
            NegotiationSession session = h.open();

            ConversationReply reply = h.service.handle(session.id(), message);

            assertSafe(h, session, reply, message);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileLlms")
    void allMessagesInOneSessionNeverBreakFloorOrLeakANumber(String name, Function<LlmRequest, String> intents) {
        ConversationHarness h = new ConversationHarness(new FakeLlmClient(intents, HOSTILE_REPLIES));
        NegotiationSession session = h.open();

        for (String message : AdversarialMessages.ALL) {
            ConversationReply reply;
            try {
                reply = h.service.handle(session.id(), message);
            } catch (SessionClosedException e) {
                break;
            }
            assertSafe(h, session, reply, message);
        }
    }

    @ParameterizedTest(name = "LLM down")
    @MethodSource("messages")
    void keywordPathIsSafeToo(String message) {
        ConversationHarness h = new ConversationHarness(FakeLlmClient.down());
        NegotiationSession session = h.open();

        assertSafe(h, session, h.service.handle(session.id(), message), message);
    }

    static List<String> messages() {
        return AdversarialMessages.ALL;
    }

    static void assertSafe(ConversationHarness h, NegotiationSession session, ConversationReply reply, String message) {
        String label = message.length() > 60 ? message.substring(0, 60) + "..." : message;
        assertThat(reply.offer()).as("offer for: %s", label).isGreaterThanOrEqualTo(ConversationHarness.FLOOR);
        assertThat(session.currentOffer()).as("engine offer for: %s", label).isGreaterThanOrEqualTo(ConversationHarness.FLOOR);
        session.agreedPrice().ifPresent(price ->
                assertThat(price).as("agreed price for: %s", label).isGreaterThanOrEqualTo(ConversationHarness.FLOOR));
        assertThat(h.amounts.extractDistinct(reply.reply())).as("numbers in reply %s for: %s", reply.reply(), label)
                .containsExactly(reply.offer());
        assertThat(reply.reply()).doesNotContainIgnoringCase("floor").doesNotContainIgnoringCase("minimum");
    }
}
