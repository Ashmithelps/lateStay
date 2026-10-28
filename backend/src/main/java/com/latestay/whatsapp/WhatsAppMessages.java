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
    public static final String BOOKING_CANCELLED = "booking_cancelled";

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

    public static ObjectNode bookingCancelled(String to, String language, String code, String reason) {
        return template(to, BOOKING_CANCELLED, language, List.of(code, reason));
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
