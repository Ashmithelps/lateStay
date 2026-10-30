package com.latestay.conversation.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider {@code anthropic}, {@code openai} (any OpenAI-compatible server) or {@code none}
 * @param priceInputPerMtokUsd the model's price per million input tokens, in US dollars, from the provider's price
 *     page (also micro-dollars per token); 0 = not recorded
 * @param priceOutputPerMtokUsd the same for output tokens
 * @param monthlyBudgetUsd once this month's recorded spend reaches it, replies come from the templates instead
 *     (prices never depend on the LLM); 0 = no limit here (set one at the provider too)
 */
@ConfigurationProperties("latestay.llm")
public record LlmProperties(
        @DefaultValue("none") String provider,
        String model,
        String apiKey,
        String baseUrl,
        @DefaultValue("8s") Duration timeout,
        @DefaultValue("0") java.math.BigDecimal priceInputPerMtokUsd,
        @DefaultValue("0") java.math.BigDecimal priceOutputPerMtokUsd,
        @DefaultValue("0") java.math.BigDecimal monthlyBudgetUsd) {
}
