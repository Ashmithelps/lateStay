package com.latestay.conversation.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the LLM costs: each bot turn's calls, tokens and cost (negotiation_turn), priced with
 * LLM_PRICE_INPUT_PER_MTOK_USD / LLM_PRICE_OUTPUT_PER_MTOK_USD. A price per million tokens in dollars is the price
 * per token in micro-dollars, so cost = tokens × price.
 */
@Component
public class LlmSpend {

    /** One bot turn's LLM use; costMicroUsd is null when no prices are configured. */
    public record TurnSpend(int calls, long inputTokens, long outputTokens, String model, Long costMicroUsd) {
    }

    private final LlmProperties properties;
    private final LlmClient client;
    private final JdbcClient jdbc;
    private final Clock clock;

    public LlmSpend(LlmProperties properties, LlmClient client, JdbcClient jdbc, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** null when no LLM call was made for the turn. */
    public TurnSpend of(LlmUsage usage) {
        if (usage == null || usage.calls() == 0) {
            return null;
        }
        return new TurnSpend(usage.calls(), usage.inputTokens(), usage.outputTokens(), client.model(),
                cost(properties, usage.inputTokens(), usage.outputTokens()));
    }

    static Long cost(LlmProperties p, long inputTokens, long outputTokens) {
        if (p.priceInputPerMtokUsd().signum() == 0 && p.priceOutputPerMtokUsd().signum() == 0) {
            return null;
        }
        return p.priceInputPerMtokUsd().multiply(BigDecimal.valueOf(inputTokens))
                .add(p.priceOutputPerMtokUsd().multiply(BigDecimal.valueOf(outputTokens)))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Recorded spend since the first of this month (UTC, as providers bill). */
    public long monthToDateMicroUsd() {
        return monthToDateMicroUsd(jdbc, clock);
    }

    static long monthToDateMicroUsd(JdbcClient jdbc, Clock clock) {
        LocalDate first = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        return jdbc.sql("SELECT COALESCE(sum(llm_cost_micro_usd), 0) FROM negotiation_turn WHERE created_at >= :from")
                .param("from", OffsetDateTime.of(first.atStartOfDay(), ZoneOffset.UTC)).query(Long.class).single();
    }
}
