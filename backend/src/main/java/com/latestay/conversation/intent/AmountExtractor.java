package com.latestay.conversation.intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds rupee amounts in free text, deterministically: digits ("700", "Rs 700", "₹1,200", "1.2k"), Hindi and
 * Punjabi number words in Latin script ("saat sau", "satt sau", "saade saat sau", "dedh hazaar") and English
 * number words ("seven hundred", "seven fifty").
 *
 * <p>Bare numbers count only between 100 and 99,999, so "2 log", "11:30" and phone numbers are ignored. Amounts
 * with a currency marker count at any size.
 */
public final class AmountExtractor {

    public static final long MIN_BARE_AMOUNT = 100;
    public static final long MAX_BARE_AMOUNT = 99_999;

    private static final String DIGITS = "(\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.(\\d+))?";

    private static final Pattern CURRENCY = Pattern.compile(
            "(?:₹|\\brs\\.?|\\binr\\b)\\s*" + DIGITS + "\\s*(k\\b)?");
    private static final Pattern DIGITS_WITH_MULTIPLIER = Pattern.compile(
            "(?<![\\d.,:])" + DIGITS + "\\s*(k|sau|hundred|hazaar|hazar|hajar|thousand)\\b");
    private static final Pattern BARE = Pattern.compile("(?<![\\d.,:])" + DIGITS + "(?![\\d:]|\\.\\d|\\s*(?:am|pm)\\b)");
    private static final Pattern WORD = Pattern.compile("[a-z]+");

    private static final Map<String, Double> HINDI_UNITS = Map.ofEntries(
            Map.entry("ek", 1.0), Map.entry("do", 2.0), Map.entry("teen", 3.0), Map.entry("tin", 3.0),
            Map.entry("char", 4.0), Map.entry("chaar", 4.0),
            Map.entry("paanch", 5.0), Map.entry("panch", 5.0), Map.entry("panj", 5.0), Map.entry("punj", 5.0),
            Map.entry("chhe", 6.0), Map.entry("che", 6.0), Map.entry("chhah", 6.0), Map.entry("chhey", 6.0),
            Map.entry("chah", 6.0),
            Map.entry("saat", 7.0), Map.entry("sat", 7.0), Map.entry("satt", 7.0),
            Map.entry("aath", 8.0), Map.entry("aat", 8.0), Map.entry("ath", 8.0), Map.entry("atth", 8.0),
            Map.entry("nau", 9.0),
            Map.entry("das", 10.0), Map.entry("dus", 10.0),
            Map.entry("dedh", 1.5), Map.entry("dhai", 2.5), Map.entry("dhaai", 2.5), Map.entry("dhaee", 2.5),
            Map.entry("pachas", 50.0), Map.entry("pachaas", 50.0), Map.entry("panjah", 50.0));
    private static final Map<String, Double> HINDI_MODIFIERS = Map.of(
            "saade", 0.5, "sade", 0.5, "sadhe", 0.5, "saadhe", 0.5,
            "sava", 0.25, "sawa", 0.25, "paune", -0.25, "pone", -0.25);
    private static final Map<String, Integer> HINDI_MULTIPLIERS = Map.of(
            "sau", 100, "hazaar", 1000, "hazar", 1000, "hajar", 1000, "hazzar", 1000);

    private static final Map<String, Integer> ENGLISH_SMALL = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
            Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15),
            Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
            Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40),
            Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80),
            Map.entry("ninety", 90));

    private record Found(int start, long amount) {
    }

    /** Every amount in order of appearance; repeats are kept. */
    public List<Long> extractAll(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        StringBuilder masked = new StringBuilder(text.toLowerCase(Locale.ROOT));
        List<Found> found = new ArrayList<>();

        Matcher currency = CURRENCY.matcher(masked);
        while (currency.find()) {
            long amount = toAmount(currency.group(1), currency.group(2), currency.group(3) != null ? 1000 : 1);
            if (amount > 0) {
                found.add(new Found(currency.start(), amount));
            }
            mask(masked, currency.start(), currency.end());
            currency = CURRENCY.matcher(masked);
        }

        Matcher multiplied = DIGITS_WITH_MULTIPLIER.matcher(masked);
        while (multiplied.find()) {
            int multiplier = switch (multiplied.group(3)) {
                case "sau", "hundred" -> 100;
                default -> 1000;
            };
            long amount = toAmount(multiplied.group(1), multiplied.group(2), multiplier);
            if (amount > 0) {
                found.add(new Found(multiplied.start(), amount));
            }
            mask(masked, multiplied.start(), multiplied.end());
            multiplied = DIGITS_WITH_MULTIPLIER.matcher(masked);
        }

        Matcher bare = BARE.matcher(masked);
        while (bare.find()) {
            long amount = toAmount(bare.group(1), bare.group(2), 1);
            if (amount >= MIN_BARE_AMOUNT && amount <= MAX_BARE_AMOUNT) {
                found.add(new Found(bare.start(), amount));
            }
        }

        found.addAll(wordAmounts(masked.toString()));
        found.sort(Comparator.comparingInt(Found::start));
        return found.stream().map(Found::amount).toList();
    }

    /** Distinct amounts in order of first appearance. */
    public List<Long> extractDistinct(String text) {
        return extractAll(text).stream().distinct().toList();
    }

    private static void mask(StringBuilder text, int start, int end) {
        for (int i = start; i < end; i++) {
            text.setCharAt(i, ' ');
        }
    }

    private static long toAmount(String whole, String fraction, int multiplier) {
        double value = Double.parseDouble(whole.replace(",", "") + (fraction == null ? "" : "." + fraction));
        return Math.round(value * multiplier);
    }

    /** Runs of number words, read as Hindi/Punjabi or English depending on the words used. */
    private static List<Found> wordAmounts(String text) {
        List<Found> found = new ArrayList<>();
        Matcher word = WORD.matcher(text);
        List<String> run = new ArrayList<>();
        int runStart = -1;
        while (word.find()) {
            String w = word.group();
            if (isNumberWord(w) || (!run.isEmpty() && w.equals("and"))) {
                if (run.isEmpty()) {
                    runStart = word.start();
                }
                run.add(w);
            } else {
                flush(run, runStart, found);
            }
        }
        flush(run, runStart, found);
        return found;
    }

    private static boolean isNumberWord(String w) {
        return HINDI_UNITS.containsKey(w) || HINDI_MODIFIERS.containsKey(w) || HINDI_MULTIPLIERS.containsKey(w)
                || ENGLISH_SMALL.containsKey(w) || w.equals("hundred") || w.equals("thousand");
    }

    private static void flush(List<String> run, int start, List<Found> found) {
        if (run.isEmpty()) {
            return;
        }
        while (!run.isEmpty() && run.getLast().equals("and")) {
            run.removeLast();
        }
        // A run can start with a stray word ("do seven hundred"): drop leading words until it reads.
        for (int i = 0; i < run.size(); i++) {
            List<String> tail = run.subList(i, run.size());
            long amount = Math.max(readHindi(tail), readEnglish(tail));
            if (amount >= MIN_BARE_AMOUNT) {
                found.add(new Found(start, amount));
                break;
            }
        }
        run.clear();
    }

    /** "saade saat sau" = 750, "ek hazaar do sau" = 1200, "dedh hazaar" = 1500. Needs a sau/hazaar. */
    private static long readHindi(List<String> words) {
        double total = 0;
        Double current = null;
        double modifier = 0;
        boolean sawMultiplier = false;
        for (String w : words) {
            if (HINDI_MODIFIERS.containsKey(w)) {
                modifier = HINDI_MODIFIERS.get(w);
            } else if (HINDI_UNITS.containsKey(w)) {
                current = HINDI_UNITS.get(w) + modifier;
                modifier = 0;
            } else if (HINDI_MULTIPLIERS.containsKey(w)) {
                total += (current == null ? 1 : current) * HINDI_MULTIPLIERS.get(w);
                current = null;
                sawMultiplier = true;
            } else if (!w.equals("and")) {
                return 0;
            }
        }
        if (!sawMultiplier) {
            return 0;
        }
        return Math.round(total + (current == null ? 0 : current));
    }

    /** "seven hundred" = 700, "seven fifty" = 750, "one thousand two hundred" = 1200. */
    private static long readEnglish(List<String> words) {
        if (words.size() == 2 && ENGLISH_SMALL.containsKey(words.get(0)) && ENGLISH_SMALL.containsKey(words.get(1))) {
            int hundreds = ENGLISH_SMALL.get(words.get(0));
            int rest = ENGLISH_SMALL.get(words.get(1));
            if (hundreds < 20 && rest >= 10) {
                return hundreds * 100L + rest;
            }
        }
        long total = 0;
        long current = 0;
        for (String w : words) {
            if (ENGLISH_SMALL.containsKey(w)) {
                current += ENGLISH_SMALL.get(w);
            } else if (w.equals("hundred")) {
                current = (current == 0 ? 1 : current) * 100;
            } else if (w.equals("thousand")) {
                total += (current == 0 ? 1 : current) * 1000;
                current = 0;
            } else if (!w.equals("and")) {
                return 0;
            }
        }
        return total + current;
    }
}
