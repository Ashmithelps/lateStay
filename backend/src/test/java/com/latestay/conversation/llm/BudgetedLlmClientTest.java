package com.latestay.conversation.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latestay.TestClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BudgetedLlmClientTest {

    static final LlmRequest REQUEST = LlmRequest.text("system", List.of(), 100);

    @Test
    void callsStopAtTheMonthlyBudgetAndResumeWhenSpendDrops() {
        AtomicLong spent = new AtomicLong(9_990_000); // $9.99
        TestClock clock = new TestClock(Instant.parse("2026-11-11T10:00:00Z"));
        LlmClient ok = request -> new LlmResponse("hi", 10, 2);
        BudgetedLlmClient client = new BudgetedLlmClient(ok, new BigDecimal("10"), spent::get, clock);

        assertThat(client.complete(REQUEST).text()).isEqualTo("hi");
        spent.set(10_000_000);
        // Re-read at most once a minute.
        assertThat(client.complete(REQUEST).text()).isEqualTo("hi");
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("budget");

        spent.set(0); // a new month
        clock.advance(Duration.ofSeconds(61));
        assertThat(client.complete(REQUEST).text()).isEqualTo("hi");
    }

    @Test
    void costIsTokensTimesThePricePerMillion() {
        LlmProperties priced = new LlmProperties("anthropic", "m", "k", null, Duration.ofSeconds(8),
                new BigDecimal("0.80"), new BigDecimal("4"), BigDecimal.ZERO);
        assertThat(LlmSpend.cost(priced, 1_000, 200)).isEqualTo(800 + 800);
        LlmProperties unpriced = new LlmProperties("anthropic", "m", "k", null, Duration.ofSeconds(8),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(LlmSpend.cost(unpriced, 1_000, 200)).isNull();
    }
}
