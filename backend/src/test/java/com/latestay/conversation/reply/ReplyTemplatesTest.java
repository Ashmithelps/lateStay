package com.latestay.conversation.reply;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.conversation.ChatMessage;
import com.latestay.conversation.HotelFacts;
import com.latestay.conversation.intent.AmountExtractor;
import com.latestay.conversation.intent.Register;
import com.latestay.negotiation.EngineAction;
import com.latestay.negotiation.EngineDecision;
import com.latestay.negotiation.ReasonCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReplyTemplatesTest {

    static final HotelFacts FACTS = new HotelFacts("Test Lodge", List.of("Room heater", "Free parking"),
            List.of("Check-in with photo ID"));

    private final ReplyValidator validator = new ReplyValidator(new AmountExtractor());
    private final ReplyTemplates templates = new ReplyTemplates(validator);

    @ParameterizedTest
    @EnumSource(ReasonCode.class)
    void everyReasonCodeTemplatePassesValidationInBothRegisters(ReasonCode reason) {
        for (Register register : Register.values()) {
            for (EngineAction action : EngineAction.values()) {
                for (long amount : new long[] {450, 1000, 12500}) {
                    String reply = templates.render(context(new EngineDecision(action, amount, reason), register, null));
                    assertThat(validator.validate(reply, amount).valid()).as(reply).isTrue();
                }
            }
        }
    }

    @Test
    void questionTemplatesAnswerKnownAmenityAndRestateOffer() {
        EngineDecision hold = new EngineDecision(EngineAction.HOLD, 900, ReasonCode.UNCLEAR);

        assertThat(templates.render(context(hold, Register.HINGLISH, "heater hai?")))
                .isEqualTo("Haan, room heater hai. Room abhi ₹900 ka hai.");
        assertThat(templates.render(context(hold, Register.ENGLISH, "Is there a swimming pool?")))
                .isEqualTo("Let me check that with the owner. The room is ₹900 for tonight.");
    }

    @Test
    void finalOfferAboveFloorUsesFinalWording() {
        EngineDecision decision = new EngineDecision(EngineAction.FINAL_OFFER, 750, ReasonCode.MEETING_HALFWAY);

        assertThat(templates.render(context(decision, Register.ENGLISH, null)))
                .isEqualTo("₹750 is the best I can do tonight.");
    }

    static ReplyContext context(EngineDecision decision, Register register, String question) {
        return new ReplyContext(decision, FACTS, register, List.of(ChatMessage.traveller("hi")), question);
    }
}
