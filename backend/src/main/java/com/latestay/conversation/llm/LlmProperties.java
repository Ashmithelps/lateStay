package com.latestay.conversation.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider {@code anthropic}, {@code openai} (any OpenAI-compatible server) or {@code none}
 */
@ConfigurationProperties("latestay.llm")
public record LlmProperties(
        @DefaultValue("none") String provider,
        String model,
        String apiKey,
        String baseUrl,
        @DefaultValue("8s") Duration timeout) {
}
