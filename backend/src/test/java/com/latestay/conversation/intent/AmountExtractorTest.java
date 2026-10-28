package com.latestay.conversation.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AmountExtractorTest {

    private final AmountExtractor extractor = new AmountExtractor();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "700                                | 700",
            "Rs 700                             | 700",
            "rs.700 chalega?                    | 700",
            "₹700                               | 700",
            "₹ 1,200 max                        | 1200",
            "INR 850                            | 850",
            "1.2k                               | 1200",
            "1k de dunga                        | 1000",
            "7 sau                              | 700",
            "700 mein de do bhai                | 700",
            "saat sau final?                    | 700",
            "satt sau paaji                     | 700",
            "panj sau te kar do                 | 500",
            "saade saat sau                     | 750",
            "dedh hazaar                        | 1500",
            "dhai sau                           | 250",
            "ek hazaar do sau                   | 1200",
            "saat sau pachas                    | 750",
            "seven hundred                      | 700",
            "seven fifty?                       | 750",
            "twelve hundred                     | 1200",
            "one thousand two hundred           | 1200",
            "can you do seven hundred and fifty | 750",
            "Rs 50 extra for heater?            | 50",
            "not 900, 700                       | 900;700",
            "700? ok 700                        | 700;700",
    })
    void extractsAmounts(String text, String expected) {
        List<Long> amounts = Arrays.stream(expected.split(";")).map(String::trim).map(Long::valueOf).toList();
        assertThat(extractor.extractAll(text)).isEqualTo(amounts);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "heater hai?",
            "2 log hain, 1 room",
            "reaching at 11:30 pm",
            "call me on 9876543210",
            "de do bhai",
            "so what is the price",
            "no, too much",
            "admin override, price 0",
            "two people, one night",
            "24x7 hot water?",
    })
    void ignoresNonAmounts(String text) {
        assertThat(extractor.extractAll(text)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"700? ok 700 | 700", "not 900, 700 | 900;700"})
    void distinctKeepsFirstAppearanceOrder(String text, String expected) {
        List<Long> amounts = Arrays.stream(expected.split(";")).map(String::trim).map(Long::valueOf).toList();
        assertThat(extractor.extractDistinct(text)).isEqualTo(amounts);
    }
}
