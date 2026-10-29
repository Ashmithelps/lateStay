package com.latestay.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PiiTest {

    @Test
    void masksAllButTheLastFourDigits() {
        assertThat(Pii.mask("+919876543210")).isEqualTo("+91•••••3210");
        assertThat(Pii.mask("919876543210")).isEqualTo("+91•••••3210");
        assertThat(Pii.mask("98765 43210")).isEqualTo("•••••3210");
        assertThat(Pii.mask("09876543210")).isEqualTo("•••••3210");
        assertThat(Pii.mask("12")).isEqualTo("•••");
        assertThat(Pii.mask(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+919876543210", "919876543210", "+91 98765 43210", "09876543210", "98765-43210",
            "9876543210"})
    void scrubsPhonesInsideText(String phone) {
        String scrubbed = Pii.scrub("Failing row contains (abc, Asha, " + phone + ", 2).");
        assertThat(scrubbed).doesNotContain("98765").contains("3210").startsWith("Failing row contains (abc, Asha, ");
    }

    @Test
    void leavesOtherNumbersAlone() {
        String text = "booking 5f1e2d3c-4b5a-4968 code 4821 amount 20000 paise at 1794416400 order_9A33XWu170gUtm";
        assertThat(Pii.scrub(text)).isEqualTo(text);
        assertThat(Pii.scrub(null)).isNull();
    }
}
