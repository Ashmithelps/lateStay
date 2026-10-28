package com.latestay.conversation.reply;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.conversation.intent.AmountExtractor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReplyValidatorTest {

    private final ReplyValidator validator = new ReplyValidator(new AmountExtractor());

    @ParameterizedTest
    @ValueSource(strings = {
            "The room is ₹1,000 for tonight.",
            "Room ₹1000 ka hai, aa jaiye.",
            "I can do Rs. 1000 for tonight. See you soon!",
            "Okay, 1000 it is.",
            "Ek hazaar mein ho jayega, ₹1,000 final.",
            "Check out by ten in the morning, and the room is ₹1,000.",
    })
    void acceptsRepliesWithOnlyTheApprovedAmount(String reply) {
        assertThat(validator.validate(reply, 1000).valid()).as(reply).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The room is ₹900 for tonight.",
            "Down from ₹1,200, now ₹1,000.",
            "₹1,000, and our minimum is 700.",
            "₹1,000 tonight, saat sau is not possible.",
            "₹1,000 or Rs 50 less if you pay cash.",
            "Room is available, come over.",
            "",
            "₹1,000. Heater included. Parking free. Late check-in fine.",
    })
    void rejectsOtherAmountsMissingAmountOrTooManySentences(String reply) {
        assertThat(validator.validate(reply, 1000).valid()).as(reply).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x"})
    void rejectsOverlongReplies(String unused) {
        assertThat(validator.validate("₹1,000 " + "very ".repeat(80), 1000).valid()).isFalse();
    }
}
