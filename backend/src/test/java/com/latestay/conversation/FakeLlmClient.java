package com.latestay.conversation;

import com.latestay.conversation.llm.LlmClient;
import com.latestay.conversation.llm.LlmRequest;
import com.latestay.conversation.llm.LlmResponse;
import com.latestay.conversation.llm.LlmUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Scriptable LLM: one function answers intent (JSON) requests, another answers reply requests. Every answer
 * reports {@link #INPUT_TOKENS} in and {@link #OUTPUT_TOKENS} out.
 */
public final class FakeLlmClient implements LlmClient {

    public static final long INPUT_TOKENS = 100;
    public static final long OUTPUT_TOKENS = 20;

    private final Function<LlmRequest, String> intents;
    private final Function<LlmRequest, String> replies;
    private final List<LlmRequest> intentRequests = new ArrayList<>();
    private final List<LlmRequest> replyRequests = new ArrayList<>();

    public FakeLlmClient(Function<LlmRequest, String> intents, Function<LlmRequest, String> replies) {
        this.intents = intents;
        this.replies = replies;
    }

    public static FakeLlmClient down() {
        Function<LlmRequest, String> fail = r -> {
            throw new LlmUnavailableException("down");
        };
        return new FakeLlmClient(fail, fail);
    }

    public static Function<LlmRequest, String> timeout() {
        return r -> {
            throw new LlmUnavailableException("timed out", null, true);
        };
    }

    public static String intentJson(String type, Long amount, String question, String register) {
        return """
                {"type": "%s", "amount": %s, "question": %s, "register": "%s"}"""
                .formatted(type, amount, question == null ? "null" : "\"" + question + "\"", register);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String text;
        if (request.wantsJson()) {
            intentRequests.add(request);
            text = intents.apply(request);
        } else {
            replyRequests.add(request);
            text = replies.apply(request);
        }
        return new LlmResponse(text, INPUT_TOKENS, OUTPUT_TOKENS);
    }

    public List<LlmRequest> intentRequests() {
        return intentRequests;
    }

    public List<LlmRequest> replyRequests() {
        return replyRequests;
    }
}
