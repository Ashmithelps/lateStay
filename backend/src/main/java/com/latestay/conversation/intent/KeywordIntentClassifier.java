package com.latestay.conversation.intent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic intent rules, used when the LLM is unavailable or returns something invalid. */
public final class KeywordIntentClassifier {

    private static final Pattern ASK_BEST = Pattern.compile(
            "\\b(best|lowest|last|final|aakhri|akhri|kam\\s+kar\\w*|thoda\\s+kam|discount|kitne\\s+mein\\s+(?:doge|do|hoga|ho\\s+jayega)|kuch\\s+kam)\\b");
    private static final Pattern ACCEPT = Pattern.compile(
            "\\b(ok|okay|okk|done|deal|accept\\w*|book\\s+it|confirm\\w*|theek\\s+hai|thik\\s+hai|thik\\s+h|chalo|chaliye|haan|ha+n|yes|pakka|le\\s+lete|le\\s+lenge|chalega|sahi\\s+hai)\\b");
    private static final Pattern NEGATION = Pattern.compile("\\b(not|no|nahi|nahin|nai|mat|never)\\b");
    private static final Pattern QUESTION_WORDS = Pattern.compile(
            "^(what|is|are|do|does|can|where|when|how|kya|kahan|kab|kaise|kitna|kitne|ki)\\b|\\b(hai|h|hain|milega|milegi|hoga|ho\\s+sakta)\\s*\\?");
    private static final List<String> HINGLISH_WORDS = List.of(
            "hai", "hain", "bhai", "bhaiya", "mein", "main", "kya", "nahi", "nahin", "kar", "karo", "karna",
            "chalega", "theek", "thik", "accha", "acha", "achha", "ji", "yaar", "paaji", "veere", "sau", "hazaar",
            "hazar", "kitna", "kitne", "kam", "wala", "wali", "dena", "denge", "doge", "de", "lenge", "raat",
            "kamra", "milega", "tusi", "sanu", "dasso", "aaj", "abhi", "pakka", "chalo", "haan", "bhool", "jao");

    private final AmountExtractor amounts;

    public KeywordIntentClassifier(AmountExtractor amounts) {
        this.amounts = amounts;
    }

    public Intent classify(String text) {
        String lower = text.toLowerCase(Locale.ROOT).strip();
        Register register = register(lower);
        List<Long> found = amounts.extractDistinct(lower);

        if (found.size() == 1) {
            return new Intent(IntentType.COUNTER, found.getFirst(), null, register);
        }
        if (found.size() > 1) {
            // Several prices and no LLM to read them: don't guess which one is meant.
            return new Intent(IntentType.OTHER, null, null, register);
        }
        if (ASK_BEST.matcher(lower).find()) {
            return new Intent(IntentType.ASK_BEST, null, null, register);
        }
        if (lower.contains("?") || QUESTION_WORDS.matcher(lower).find()) {
            return new Intent(IntentType.QUESTION, null, text.strip(), register);
        }
        if (ACCEPT.matcher(lower).find() && !NEGATION.matcher(lower).find()) {
            return new Intent(IntentType.ACCEPT, null, null, register);
        }
        return new Intent(IntentType.OTHER, null, null, register);
    }

    public Register register(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : lower.split("[^a-z]+")) {
            if (HINGLISH_WORDS.contains(word)) {
                return Register.HINGLISH;
            }
        }
        return Register.ENGLISH;
    }
}
