package com.latestay.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.whatsapp.OwnerReply.Action;
import com.latestay.whatsapp.OwnerReply.BookingAnswer;
import com.latestay.whatsapp.OwnerReply.RoomsFree;
import com.latestay.whatsapp.OwnerReply.Unknown;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OwnerReplyParserTest {

    static final UUID HOTEL = UUID.fromString("0b6f3c5e-8c1a-4a57-9d55-1d0f6a2c3b4e");
    static final UUID BOOKING = UUID.fromString("5f1e2d3c-4b5a-4968-8776-655443322110");
    static final LocalDate NIGHT = LocalDate.of(2026, 11, 11);

    // Buttons

    @ParameterizedTest(name = "button {0} -> {1} rooms")
    @CsvSource({"0, 0", "1, 1", "2, 2", "3+, 3"})
    void nightlyPingButtonsCarryHotelNightAndRooms(String button, int rooms) {
        String payload = OwnerReplyParser.availabilityPayload(HOTEL, NIGHT, button);
        assertThat(OwnerReplyParser.parse(payload, button)).isEqualTo(new RoomsFree(rooms, HOTEL, NIGHT));
    }

    @Test
    void bookingButtonsCarryTheBookingAndTheAnswer() {
        assertThat(OwnerReplyParser.parse(OwnerReplyParser.bookingPayload(BOOKING, Action.CONFIRM), "Confirm"))
                .isEqualTo(new BookingAnswer(BOOKING, Action.CONFIRM));
        assertThat(OwnerReplyParser.parse(OwnerReplyParser.bookingPayload(BOOKING, Action.DECLINE), "Can't take it"))
                .isEqualTo(new BookingAnswer(BOOKING, Action.DECLINE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AVAIL|not-a-uuid|2026-11-11|2", "AVAIL|0b6f3c5e-8c1a-4a57-9d55-1d0f6a2c3b4e|tonight|2",
            "AVAIL|0b6f3c5e-8c1a-4a57-9d55-1d0f6a2c3b4e|2026-11-11|99", "BOOKING|5f1e2d3c-4b5a-4968-8776-655443322110|MAYBE",
            "BOOKING|x", "SOMETHING_ELSE"})
    void foreignOrBrokenPayloadsFallBackToTheButtonText(String payload) {
        assertThat(OwnerReplyParser.parse(payload, "Confirm")).isInstanceOf(Unknown.class);
        assertThat(OwnerReplyParser.parse(payload, "2")).isEqualTo(new RoomsFree(2, null, null));
    }

    // Free text: English and Hinglish

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "rooms 2|2",
            "Rooms: 3|3",
            "room 1|1",
            "2 rooms|2",
            "2 rooms free|2",
            "2 rooms available tonight|2",
            "1 room left|1",
            "two rooms free|2",
            "today 4 rooms free|4",
            "2|2",
            "0|0",
            "3+|3",
            "12|12",
            "2 room khali|2",
            "2 room khali hai|2",
            "do room khali hai|2",
            "Do rooms khaali hain!|2",
            "teen kamre khali|3",
            "ek kamra khali hai|1",
            "aaj 5 room khali hai|5",
            "rooms 2.|2",
            "char kamre bache hain|4",
            "das room|10",
    })
    void roomCounts(String text, int rooms) {
        assertThat(OwnerReplyParser.parse(null, text)).isEqualTo(new RoomsFree(rooms, null, null));
    }

    @ParameterizedTest(name = "\"{0}\" -> 0")
    @ValueSource(strings = {"full", "Full hai", "hotel full", "sab full", "housefull", "no rooms", "no room free",
            "koi room nahi", "koi room khali nahi", "room nahi hai", "rooms nahi", "zero rooms", "0 rooms", "kamre nahi hain"})
    void noRooms(String text) {
        assertThat(OwnerReplyParser.parse(null, text)).isEqualTo(new RoomsFree(0, null, null));
    }

    @ParameterizedTest(name = "\"{0}\" is not understood")
    @ValueSource(strings = {"", "   ", "12345", "₹800", "800 rupees", "2 guests", "kal baat karte hain", "ok",
            "Confirm", "yes", "do", "rooms", "2 rooms for 3 nights", "call me", "hello", "48210", "12 34", "1234 rooms",
            "my account balance", "hisaab kal"})
    void everythingElseIsUnknown(String text) {
        assertThat(OwnerReplyParser.parse(null, text)).isInstanceOf(Unknown.class);
    }

    /** 1-3 digits are always a room count; the handler answers "0 to 50" for the ones above the limit. */
    @ParameterizedTest(name = "\"{0}\" -> {1}, too many to list")
    @CsvSource(delimiter = '|', value = {"51|51", "rooms 51|51", "99 rooms|99", "120|120", "999 room khali|999"})
    void oneToThreeDigitsStayRoomCountsAboveTheLimit(String text, int rooms) {
        assertThat(rooms).isGreaterThan(OwnerReplyParser.MAX_ROOMS);
        assertThat(OwnerReplyParser.parse(null, text)).isEqualTo(new RoomsFree(rooms, null, null));
    }

    @ParameterizedTest(name = "\"{0}\" asks for the account link")
    @ValueSource(strings = {"hisaab", "Hisab", "HISAAB!", "account", "Account please", "earnings", "my earnings",
            "hisaab bhejo", "hisaab kitab"})
    void accountKeywordsAskForTheOwnerPage(String text) {
        assertThat(OwnerReplyParser.parse(null, text)).isInstanceOf(OwnerReply.AccountLink.class);
    }

    @ParameterizedTest(name = "\"{0}\" is the desk code {1}")
    @CsvSource(delimiter = '|', value = {"4821|4821", "0042|0042", "code 4821|4821", "Code: 4821|4821", " 4821 |4821"})
    void fourDigitsMayBeADeskCode(String text, String code) {
        assertThat(OwnerReplyParser.parse(null, text)).isEqualTo(new OwnerReply.DeskCode(code));
    }

    @Test
    void arrivalCheckButtonsCarryTheBookingAndTheAnswer() {
        assertThat(OwnerReplyParser.parse(OwnerReplyParser.arrivalPayload(BOOKING, true), "Arrived"))
                .isEqualTo(new OwnerReply.ArrivalAnswer(BOOKING, true));
        assertThat(OwnerReplyParser.parse(OwnerReplyParser.arrivalPayload(BOOKING, false), "Didn't arrive"))
                .isEqualTo(new OwnerReply.ArrivalAnswer(BOOKING, false));
        assertThat(OwnerReplyParser.parse("ARRIVAL|" + BOOKING + "|MAYBE", "Arrived")).isInstanceOf(Unknown.class);
    }

    @Test
    void nullTextWithoutAButtonIsUnknown() {
        assertThat(OwnerReplyParser.parse(null, null)).isInstanceOf(Unknown.class);
    }
}
