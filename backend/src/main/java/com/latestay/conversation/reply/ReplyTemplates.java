package com.latestay.conversation.reply;

import com.latestay.conversation.intent.Register;
import com.latestay.negotiation.EngineAction;
import com.latestay.negotiation.EngineDecision;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Fixed replies, used when the LLM is unavailable or its drafts fail validation. */
@Component
public class ReplyTemplates {

    private final ReplyValidator validator;

    public ReplyTemplates(ReplyValidator validator) {
        this.validator = validator;
    }

    public String render(ReplyContext context) {
        String price = Money.format(context.amount());
        boolean hinglish = context.register() == Register.HINGLISH;

        if (context.isQuestion()) {
            String amenity = matchingAmenity(context);
            if (amenity != null) {
                String reply = hinglish
                        ? "Haan, " + amenity.toLowerCase(Locale.ROOT) + " hai. Room abhi " + price + " ka hai."
                        : "Yes, we have " + amenity.toLowerCase(Locale.ROOT) + ". The room is " + price + " for tonight.";
                if (validator.validate(reply, context.amount()).valid()) {
                    return reply;
                }
            }
            return hinglish
                    ? "Yeh main owner se pooch ke batata hoon. Room abhi " + price + " ka hai."
                    : "Let me check that with the owner. The room is " + price + " for tonight.";
        }

        EngineDecision decision = context.decision();
        if (decision.action() == EngineAction.FINAL_OFFER) {
            return hinglish
                    ? price + " aaj raat ka best price hai."
                    : price + " is the best I can do tonight.";
        }
        return switch (decision.reasonCode()) {
            case OPENING, LATE_HOUR -> hinglish
                    ? "Aaj raat ke liye room " + price + " ka hai."
                    : "The room is " + price + " for tonight.";
            case COUNTER_TOO_LOW -> hinglish
                    ? "Itna kam nahi ho payega, par " + price + " mein de sakte hain."
                    : "That's too low for us, but I can do " + price + " for tonight.";
            case MEETING_HALFWAY -> hinglish
                    ? "Chaliye beech ka rakhte hain, " + price + " mein ho jayega."
                    : "Let's meet in the middle: " + price + " for tonight.";
            case FLOOR_REACHED -> hinglish
                    ? price + " aaj raat ka best price hai."
                    : price + " is the best I can do tonight.";
            case CLOSE_ENOUGH, ACCEPTED -> hinglish
                    ? "Done, " + price + " pakka. Room taiyaar karwa dete hain."
                    : "Deal at " + price + ". I'll get your room ready.";
            case ALREADY_FINAL -> hinglish
                    ? "Maaf kijiye, " + price + " hi final tha."
                    : "Sorry, " + price + " was my final price for tonight.";
            case UNCLEAR -> hinglish
                    ? "Maaf kijiye, samjha nahi. Room " + price + " ka hai, aap kitna dena chahenge?"
                    : "Sorry, I didn't catch that. The room is " + price + " tonight, what would you like to pay?";
        };
    }

    private static String matchingAmenity(ReplyContext context) {
        String question = context.question().toLowerCase(Locale.ROOT);
        for (String amenity : context.facts().amenities()) {
            for (String word : amenity.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                if (word.length() >= 5 && question.contains(word)) {
                    return amenity;
                }
            }
        }
        return null;
    }
}
