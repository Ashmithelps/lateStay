package com.latestay.conversation.reply;

import com.latestay.conversation.intent.AmountExtractor;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks a drafted reply before it reaches the traveller: the approved amount must appear and no other
 * money-looking number may. Also enforces the two-sentence limit.
 */
@Component
public class ReplyValidator {

    static final int MAX_SENTENCES = 2;
    static final int MAX_CHARS = 300;

    private static final Pattern RS_ABBREVIATION = Pattern.compile("(?i)\\brs\\.");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?।]+(?=\\s|$)");

    private final AmountExtractor amounts;

    public ReplyValidator(AmountExtractor amounts) {
        this.amounts = amounts;
    }

    public record Result(boolean valid, String problem) {

        static Result ok() {
            return new Result(true, null);
        }

        static Result invalid(String problem) {
            return new Result(false, problem);
        }
    }

    public Result validate(String reply, long approvedAmount) {
        if (reply == null || reply.isBlank()) {
            return Result.invalid("empty");
        }
        if (reply.length() > MAX_CHARS) {
            return Result.invalid("too long");
        }

        List<Long> money = amounts.extractDistinct(reply);
        if (!money.contains(approvedAmount)) {
            return Result.invalid("approved amount missing");
        }
        if (money.size() > 1) {
            return Result.invalid("unapproved amount " + money.stream().filter(a -> a != approvedAmount).toList());
        }

        String forCounting = RS_ABBREVIATION.matcher(reply.strip()).replaceAll("rs");
        long sentences = SENTENCE_END.splitAsStream(forCounting).filter(s -> !s.isBlank()).count();
        if (sentences > MAX_SENTENCES) {
            return Result.invalid("more than two sentences");
        }
        return Result.ok();
    }
}
