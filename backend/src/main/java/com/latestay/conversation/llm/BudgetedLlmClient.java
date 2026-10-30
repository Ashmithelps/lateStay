package com.latestay.conversation.llm;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops calling the LLM once this month's recorded spend reaches LLM_MONTHLY_BUDGET_USD: calls throw
 * {@link LlmUnavailableException}, which the chat already answers with its templates. The spend is re-read at
 * most once a minute. The first refusal of a month is logged as an error, which reaches Sentry.
 */
public class BudgetedLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(BudgetedLlmClient.class);
    private static final Duration RECHECK = Duration.ofMinutes(1);

    private final LlmClient delegate;
    private final long budgetMicroUsd;
    private final LongSupplier monthToDate;
    private final Clock clock;
    private long spent;
    private Instant checkedAt = Instant.EPOCH;
    private boolean reported;

    public BudgetedLlmClient(LlmClient delegate, BigDecimal budgetUsd, LongSupplier monthToDate, Clock clock) {
        this.delegate = delegate;
        this.budgetMicroUsd = budgetUsd.movePointRight(6).longValue();
        this.monthToDate = monthToDate;
        this.clock = clock;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (overBudget()) {
            throw new LlmUnavailableException("monthly LLM budget reached");
        }
        return delegate.complete(request);
    }

    @Override
    public String model() {
        return delegate.model();
    }

    synchronized boolean overBudget() {
        Instant now = clock.instant();
        if (now.isAfter(checkedAt.plus(RECHECK)) || now.isBefore(checkedAt)) {
            spent = monthToDate.getAsLong();
            checkedAt = now;
        }
        boolean over = spent >= budgetMicroUsd;
        if (over && !reported) {
            log.error("LLM monthly budget reached ({} of {} micro-USD): replying with templates until next month",
                    spent, budgetMicroUsd);
            reported = true;
        } else if (!over) {
            reported = false;
        }
        return over;
    }
}
