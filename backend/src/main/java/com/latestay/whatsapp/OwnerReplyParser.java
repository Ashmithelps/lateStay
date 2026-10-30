package com.latestay.whatsapp;

import com.latestay.whatsapp.OwnerReply.Action;
import com.latestay.whatsapp.OwnerReply.BookingAnswer;
import com.latestay.whatsapp.OwnerReply.RoomsFree;
import com.latestay.whatsapp.OwnerReply.Unknown;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads owners' replies without an LLM: button payloads first, then a few fixed English and Hinglish phrasings of
 * "N rooms free" ("rooms 2", "2 room khali", "do kamre khali hain", "koi room nahi", "full"), a 4-digit number
 * (maybe a desk code; the handler checks it against the owner's bookings) and the account keywords. Anything else
 * is Unknown, and the owner is asked to tap a button. Numbers of 1-3 digits are always room counts, even above
 * {@link #MAX_ROOMS}, so the handler can say the number is too big rather than "I didn't get that".
 */
public final class OwnerReplyParser {

    public static final int MAX_ROOMS = 50;
    /** "3+" on the nightly ping is stored as 3. */
    public static final int THREE_PLUS = 3;

    static final String AVAILABILITY = "AVAIL";
    static final String BOOKING = "BOOKING";
    static final String ARRIVAL = "ARRIVAL";

    /** "4821", "code 4821": maybe a desk code (room counts have at most 3 digits, so 4 digits are never a count). */
    private static final Pattern DESK_CODE = Pattern.compile("^(?:code\\s*:?\\s*)?(\\d{4})$");
    /** "hisaab", "account", "earnings": the owner wants a link to their earnings page. */
    private static final Pattern ACCOUNT = Pattern.compile("^(?:my\\s+)?(?:hisaab|hisab|hisaab\\s+kitab|account|"
            + "earnings|earning)(?:\\s+(?:link|please|pls|bhejo|do|dikhao))*$");

    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("ek", 1), Map.entry("do", 2), Map.entry("teen", 3), Map.entry("tin", 3), Map.entry("char", 4),
            Map.entry("chaar", 4), Map.entry("paanch", 5), Map.entry("panch", 5), Map.entry("chhe", 6),
            Map.entry("che", 6), Map.entry("saat", 7), Map.entry("aath", 8), Map.entry("nau", 9),
            Map.entry("das", 10));

    private static final String NUMBER = "(\\d{1,3}|" + String.join("|", WORDS.keySet()) + ")(\\+)?";
    private static final String ROOM = "(?:rooms?|kamr[ae]|kamron|kamare)";
    private static final String FREE = "(?:\\s+(?:free|khali|khaali|available|empty|left|vacant|bache|bacha|baki|"
            + "hai|hain|he|h|hai ji|tonight|aaj))*";
    private static final String LEAD = "(?:(?:aaj|today|tonight|abhi|ji|haan|yes|hello|hi)\\s+)*";

    private static final Pattern ROOMS_THEN_NUMBER = Pattern.compile("^" + LEAD + ROOM + "\\s*[:=-]?\\s*" + NUMBER
            + FREE + "$");
    private static final Pattern NUMBER_THEN_ROOMS = Pattern.compile("^" + LEAD + NUMBER + "\\s*" + ROOM + FREE
            + "$");
    private static final Pattern BARE_NUMBER = Pattern.compile("^(\\d{1,3})(\\+)?$");
    private static final Pattern NONE = Pattern.compile("^" + LEAD + "(?:"
            + "(?:no|zero|koi\\s+bhi|koi)\\s+" + ROOM + "(?:\\s+(?:free|khali|khaali|available|left|nahi|nhi|nahin|"
            + "hai|hain))*"
            + "|" + ROOM + "\\s+(?:khali\\s+|free\\s+)?(?:nahi|nhi|nahin|nai)(?:\\s+(?:hai|hain|he|h))?"
            + "|(?:all|sab|sab\\s+kuch|hotel|house)?\\s*full(?:\\s+(?:hai|he|h|tonight|aaj))*"
            + "|housefull)$");

    private OwnerReplyParser() {
    }

    /**
     * @param buttonPayload the payload of a tapped quick-reply button, if any
     * @param text the typed text, or the button's label
     */
    public static OwnerReply parse(String buttonPayload, String text) {
        if (buttonPayload != null && !buttonPayload.isBlank()) {
            OwnerReply fromButton = fromPayload(buttonPayload.trim());
            if (!(fromButton instanceof Unknown)) {
                return fromButton;
            }
        }
        return text == null ? new Unknown() : fromText(text);
    }

    static OwnerReply fromPayload(String payload) {
        String[] parts = payload.split("\\|");
        try {
            if (parts.length == 4 && parts[0].equals(AVAILABILITY)) {
                int rooms = parts[3].equals("3+") ? THREE_PLUS : Integer.parseInt(parts[3]);
                if (rooms >= 0 && rooms <= MAX_ROOMS) {
                    return new RoomsFree(rooms, UUID.fromString(parts[1]), LocalDate.parse(parts[2]));
                }
            }
            if (parts.length == 3 && parts[0].equals(ARRIVAL)
                    && (parts[2].equals("ARRIVED") || parts[2].equals("NOT_ARRIVED"))) {
                return new OwnerReply.ArrivalAnswer(UUID.fromString(parts[1]), parts[2].equals("ARRIVED"));
            }
            if (parts.length == 3 && parts[0].equals(BOOKING)) {
                return new BookingAnswer(UUID.fromString(parts[1]), Action.valueOf(parts[2]));
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // a payload we didn't write
        }
        return new Unknown();
    }

    static OwnerReply fromText(String text) {
        String t = text.toLowerCase(Locale.ROOT)
                .replaceAll("[.,!?;'\"()]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        Matcher code = DESK_CODE.matcher(t);
        if (code.matches()) {
            return new OwnerReply.DeskCode(code.group(1));
        }
        if (ACCOUNT.matcher(t).matches()) {
            return new OwnerReply.AccountLink();
        }
        if (NONE.matcher(t).matches()) {
            return new RoomsFree(0, null, null);
        }
        for (Pattern pattern : new Pattern[] {ROOMS_THEN_NUMBER, NUMBER_THEN_ROOMS, BARE_NUMBER}) {
            Matcher m = pattern.matcher(t);
            if (m.matches()) {
                Integer rooms = number(m.group(1));
                if (rooms != null) {
                    return new RoomsFree(rooms, null, null);
                }
            }
        }
        return new Unknown();
    }

    private static Integer number(String token) {
        if (token.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(token);
        }
        return WORDS.get(token);
    }

    static String availabilityPayload(UUID hotelId, LocalDate nightOf, String rooms) {
        return String.join("|", AVAILABILITY, hotelId.toString(), nightOf.toString(), rooms);
    }

    static String arrivalPayload(UUID bookingId, boolean arrived) {
        return String.join("|", ARRIVAL, bookingId.toString(), arrived ? "ARRIVED" : "NOT_ARRIVED");
    }

    static String bookingPayload(UUID bookingId, Action action) {
        return String.join("|", BOOKING, bookingId.toString(), action.name());
    }
}
