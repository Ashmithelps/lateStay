package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.latestay.whatsapp.OwnerReply.Action;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Graph API request bodies. The three templates must match docs/whatsapp-templates.md exactly (name, variable
 * count and button order); everything else is a plain text reply inside the 24-hour window.
 */
public final class WhatsAppMessages {

    public static final String NIGHTLY_AVAILABILITY = "nightly_availability";
    public static final String BOOKING_REQUEST = "booking_request";
    public static final String BOOKING_REQUEST_ROOMS = "booking_request_rooms";
    public static final String BOOKING_CANCELLED = "booking_cancelled";
    public static final String ARRIVAL_CHECK = "arrival_check";
    public static final String ADMIN_ALERT = "admin_alert";
    public static final String BOOKING_CONFIRMED = "booking_confirmed";
    public static final String BOOKING_UPDATE = "booking_update";
    public static final String MORNING_DIGEST = "morning_digest";

    /** Button order of nightly_availability: 0, 1, 2, 3+. */
    static final List<String> ROOM_BUTTONS = List.of("0", "1", "2", "3+");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private WhatsAppMessages() {
    }

    public static ObjectNode nightlyAvailability(String to, String language, UUID hotelId, String hotelName,
            LocalDate nightOf) {
        ObjectNode message = template(to, NIGHTLY_AVAILABILITY, language, List.of(hotelName));
        ArrayNode components = (ArrayNode) message.path("template").path("components");
        for (int i = 0; i < ROOM_BUTTONS.size(); i++) {
            components.add(quickReply(i, OwnerReplyParser.availabilityPayload(hotelId, nightOf, ROOM_BUTTONS.get(i))));
        }
        return message;
    }

    /** Buttons: Confirm, Can't take it. */
    public static ObjectNode bookingRequest(String to, String language, UUID bookingId, int guests, LocalTime arrive,
            long payAtDesk, long paidOnline, String code) {
        ObjectNode message = template(to, BOOKING_REQUEST, language, List.of(String.valueOf(guests), time(arrive),
                rupees(payAtDesk), rupees(paidOnline), code));
        ArrayNode components = (ArrayNode) message.path("template").path("components");
        components.add(quickReply(0, OwnerReplyParser.bookingPayload(bookingId, Action.CONFIRM)));
        components.add(quickReply(1, OwnerReplyParser.bookingPayload(bookingId, Action.DECLINE)));
        return message;
    }

    /** booking_request_rooms: as booking_request, for a group's two (or more) rooms; same buttons. */
    public static ObjectNode bookingRequestRooms(String to, String language, UUID bookingId, int guests, int rooms,
            LocalTime arrive, long payAtDesk, long paidOnline, String code) {
        ObjectNode message = template(to, BOOKING_REQUEST_ROOMS, language, List.of(String.valueOf(guests),
                String.valueOf(rooms), time(arrive), rupees(payAtDesk), rupees(paidOnline), code));
        ArrayNode components = (ArrayNode) message.path("template").path("components");
        components.add(quickReply(0, OwnerReplyParser.bookingPayload(bookingId, Action.CONFIRM)));
        components.add(quickReply(1, OwnerReplyParser.bookingPayload(bookingId, Action.DECLINE)));
        return message;
    }

    public static ObjectNode bookingCancelled(String to, String language, String code, String reason) {
        return template(to, BOOKING_CANCELLED, language, List.of(code, reason));
    }

    /** Authentication template: the code goes in the body and in the copy-code button (sub_type "url"). */
    public static ObjectNode phoneOtp(String to, String language, String template, String code) {
        ObjectNode message = template(to, template, language, List.of(code));
        ArrayNode components = (ArrayNode) message.path("template").path("components");
        ObjectNode button = components.addObject();
        button.put("type", "button");
        button.put("sub_type", "url");
        button.put("index", "0");
        button.putArray("parameters").addObject().put("type", "text").put("text", code);
        return message;
    }

    /** Buttons: Arrived, Didn't arrive. */
    public static ObjectNode arrivalCheck(String to, String language, UUID bookingId, String guestName, String code) {
        ObjectNode message = template(to, ARRIVAL_CHECK, language, List.of(guestName, code));
        ArrayNode components = (ArrayNode) message.path("template").path("components");
        components.add(quickReply(0, OwnerReplyParser.arrivalPayload(bookingId, true)));
        components.add(quickReply(1, OwnerReplyParser.arrivalPayload(bookingId, false)));
        return message;
    }

    public static ObjectNode adminAlert(String to, String language, String what, String code, String hotelName) {
        return template(to, ADMIN_ALERT, language, List.of(what, code, hotelName));
    }

    /** To the traveller: hotel, address, arrive by, pay at desk, code, pass link, support link. */
    public static ObjectNode bookingConfirmed(String to, String language, String hotelName, String address,
            LocalTime arriveBy, long payAtDesk, String code, String passLink, String supportLink) {
        return template(to, BOOKING_CONFIRMED, language, List.of(hotelName, address, time(arriveBy),
                rupees(payAtDesk), code, passLink, supportLink));
    }

    /** To the traveller: one short line (no full stop; the template adds it) and the pass link. */
    public static ObjectNode bookingUpdate(String to, String language, String line, String passLink) {
        return template(to, BOOKING_UPDATE, language, List.of(line, passLink));
    }

    /** To the admin at 09:00: the 13 numbers of {@code docs/whatsapp-templates.md#morning_digest}, in order. */
    public static ObjectNode morningDigest(String to, String language, List<String> values) {
        if (values.size() != 13) {
            throw new IllegalArgumentException("morning_digest takes 13 values");
        }
        return template(to, MORNING_DIGEST, language, values);
    }

    public static ObjectNode text(String to, String body) {
        ObjectNode message = base(to);
        message.put("type", "text");
        message.putObject("text").put("preview_url", false).put("body", body);
        return message;
    }

    /** "11:30 pm" */
    public static String time(LocalTime time) {
        return TIME.format(time).toLowerCase(Locale.ENGLISH);
    }

    /** "1,100" (the template supplies the ₹) */
    public static String rupees(long amount) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(amount);
    }

    private static ObjectNode base(String to) {
        ObjectNode message = JSON.createObjectNode();
        message.put("messaging_product", "whatsapp");
        message.put("recipient_type", "individual");
        message.put("to", to);
        return message;
    }

    private static ObjectNode template(String to, String name, String language, List<String> bodyParameters) {
        ObjectNode message = base(to);
        message.put("type", "template");
        ObjectNode template = message.putObject("template");
        template.put("name", name);
        template.putObject("language").put("code", language);
        ArrayNode components = template.putArray("components");
        ObjectNode body = components.addObject();
        body.put("type", "body");
        ArrayNode parameters = body.putArray("parameters");
        for (String value : bodyParameters) {
            parameters.addObject().put("type", "text").put("text", value);
        }
        return message;
    }

    private static ObjectNode quickReply(int index, String payload) {
        ObjectNode button = JSON.createObjectNode();
        button.put("type", "button");
        button.put("sub_type", "quick_reply");
        button.put("index", String.valueOf(index));
        button.putArray("parameters").addObject().put("type", "payload").put("payload", payload);
        return button;
    }
}
