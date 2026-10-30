package com.latestay.whatsapp;

import com.latestay.booking.Actor;
import com.latestay.booking.Booking;
import com.latestay.booking.BookingRepository;
import com.latestay.booking.BookingService;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.BookingTransitions;
import com.latestay.hotel.AvailabilityRepository;
import com.latestay.hotel.AvailabilitySource;
import com.latestay.stay.LateStayTime;
import com.latestay.whatsapp.OwnerReply.Action;
import com.latestay.whatsapp.OwnerReply.BookingAnswer;
import com.latestay.whatsapp.OwnerReply.RoomsFree;
import com.latestay.whatsapp.WhatsAppMessageRepository.OwnedHotel;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.latestay.owner.OwnerSessions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Acts on an owner's message: updates tonight's availability or answers a booking, then replies with plain text
 * (always inside the 24-hour window, because it answers the owner's own message).
 */
@Component
class OwnerMessageHandler {

    static final String HELP = "Sorry, I didn't get that. Tap a button on my last message, or send the number of "
            + "rooms free tonight, like \"rooms 2\" or \"2 room khali\".";

    /** What happened, for the inbound row: error null means handled. */
    record Outcome(String error, UUID bookingId) {
        static Outcome handled() {
            return new Outcome(null, null);
        }
    }

    private final AvailabilityRepository availability;
    private final BookingService bookingService;
    private final BookingRepository bookings;
    private final WhatsAppOutbox outbox;
    private final AvailabilityPings pings;
    private final OwnerSessions ownerSessions;
    private final String webUrl;

    OwnerMessageHandler(AvailabilityRepository availability, BookingService bookingService,
            BookingRepository bookings, WhatsAppOutbox outbox, AvailabilityPings pings, OwnerSessions ownerSessions,
            @Value("${latestay.public-web-url}") String webUrl) {
        this.ownerSessions = ownerSessions;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.availability = availability;
        this.bookingService = bookingService;
        this.bookings = bookings;
        this.outbox = outbox;
        this.pings = pings;
    }

    Outcome handle(String waId, String waMessageId, List<OwnedHotel> owned, OwnerReply reply) {
        return switch (reply) {
            case RoomsFree rooms -> roomsFree(waId, waMessageId, owned, rooms);
            case BookingAnswer answer -> bookingAnswer(waId, waMessageId, owned, answer);
            case OwnerReply.ArrivalAnswer answer -> arrivalAnswer(waId, waMessageId, owned, answer);
            case OwnerReply.DeskCode code -> deskCode(waId, waMessageId, owned, code.code());
            case OwnerReply.AccountLink link -> accountLink(waId, waMessageId, owned);
            case OwnerReply.Unknown unknown -> {
                reply(waId, waMessageId, owned.getFirst().id(), null, HELP);
                yield new Outcome("not understood", null);
            }
        };
    }

    /**
     * "hisaab": a sign-in link to the owner page for each hotel on this number (free-form text: it answers the
     * owner's own message, inside the 24-hour window).
     */
    private Outcome accountLink(String waId, String waMessageId, List<OwnedHotel> owned) {
        long minutes = ownerSessions.linkValidity().toMinutes();
        StringBuilder text = new StringBuilder();
        for (OwnedHotel hotel : owned) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append("Your LateStay account for ").append(hotel.name()).append(": ").append(webUrl)
                    .append("/owner/login?t=").append(ownerSessions.newLinkToken(hotel.id()));
        }
        text.append("\n\nThe link works once, for the next ").append(minutes)
                .append(" minutes. Don't forward it: it opens your bookings and earnings.");
        reply(waId, waMessageId, owned.getFirst().id(), null, text.toString());
        return Outcome.handled();
    }

    private Outcome roomsFree(String waId, String waMessageId, List<OwnedHotel> owned, RoomsFree rooms) {
        if (rooms.rooms() > OwnerReplyParser.MAX_ROOMS) {
            reply(waId, waMessageId, owned.getFirst().id(), null, "Send a number from 0 to "
                    + OwnerReplyParser.MAX_ROOMS + " for the rooms free tonight, like \"rooms 2\".");
            return new Outcome("too many rooms", null);
        }
        Optional<OwnedHotel> hotel = rooms.hotelId() != null
                ? owned.stream().filter(h -> h.id().equals(rooms.hotelId())).findFirst()
                : owned.size() == 1 ? Optional.of(owned.getFirst()) : Optional.empty();
        if (hotel.isEmpty()) {
            String text = rooms.hotelId() != null
                    ? "That button isn't for a hotel on this number."
                    : "You manage more than one hotel on this number. Tap the button on tonight's message for the "
                            + "hotel you mean.";
            reply(waId, waMessageId, owned.getFirst().id(), null, text);
            return new Outcome(rooms.hotelId() != null ? "button for another hotel" : "ambiguous hotel", null);
        }
        // A late tap on last night's ping counts for tonight.
        LocalDate tonight = pings.tonight();
        LocalDate night = rooms.nightOf() == null || rooms.nightOf().isBefore(tonight) ? tonight : rooms.nightOf();
        availability.upsert(hotel.get().id(), night, rooms.rooms(), AvailabilitySource.WHATSAPP);

        String text = rooms.rooms() == 0
                ? "Got it: no rooms tonight at " + hotel.get().name() + ". You won't get late bookings until you "
                        + "send a new number."
                : "Got it: " + rooms.rooms() + (rooms.rooms() == 1 ? " room" : " rooms") + " free tonight at "
                        + hotel.get().name() + ". We'll message you here when a guest books.";
        reply(waId, waMessageId, hotel.get().id(), null, text);
        return Outcome.handled();
    }

    private Outcome bookingAnswer(String waId, String waMessageId, List<OwnedHotel> owned, BookingAnswer answer) {
        Optional<Booking> booking = bookings.find(answer.bookingId())
                .filter(b -> owned.stream().anyMatch(h -> h.id().equals(b.hotelId())));
        if (booking.isEmpty()) {
            reply(waId, waMessageId, owned.getFirst().id(), null, "That booking isn't for a hotel on this number.");
            return new Outcome("booking for another hotel", null);
        }
        // Lock and check first: an IllegalTransitionException thrown inside the shared transaction would mark it
        // rollback-only and lose the inbound row.
        Booking b = bookings.lock(booking.get().id()).orElseThrow();
        BookingStatus target = answer.action() == Action.CONFIRM ? BookingStatus.HOTEL_CONFIRMED
                : BookingStatus.HOTEL_DECLINED;
        if (!BookingTransitions.allowed(b.status(), target)) {
            reply(waId, waMessageId, b.hotelId(), b.id(), tooLate(b.status(), answer.action(), b));
            return new Outcome("booking was " + b.status(), b.id());
        }
        if (answer.action() == Action.CONFIRM) {
            bookingService.confirm(b.id(), Actor.HOTEL);
            reply(waId, waMessageId, b.hotelId(), b.id(), "Confirmed. " + b.guests() + " guests arriving around "
                    + WhatsAppMessages.time(LocalDateTime.ofInstant(b.arriveAt(), LateStayTime.ZONE).toLocalTime())
                    + ". They'll show code " + b.code() + " at the desk; collect ₹"
                    + WhatsAppMessages.rupees(b.payAtDesk()) + ".");
        } else {
            bookingService.decline(b.id(), Actor.HOTEL);
            reply(waId, waMessageId, b.hotelId(), b.id(), "Declined. The guest gets a full refund. Thanks for "
                    + "letting us know quickly.");
        }
        return new Outcome(null, b.id());
    }

    /** arrival_check: Arrived -> CHECKED_IN, Didn't arrive -> NO_SHOW (the traveller can dispute it once). */
    private Outcome arrivalAnswer(String waId, String waMessageId, List<OwnedHotel> owned,
            OwnerReply.ArrivalAnswer answer) {
        Optional<Booking> found = bookings.find(answer.bookingId())
                .filter(b -> owned.stream().anyMatch(h -> h.id().equals(b.hotelId())));
        if (found.isEmpty()) {
            reply(waId, waMessageId, owned.getFirst().id(), null, "That booking isn't for a hotel on this number.");
            return new Outcome("booking for another hotel", null);
        }
        Booking b = bookings.lock(found.get().id()).orElseThrow();
        BookingStatus target = answer.arrived() ? BookingStatus.CHECKED_IN : BookingStatus.NO_SHOW;
        if (b.status() == target) {
            reply(waId, waMessageId, b.hotelId(), b.id(), "Already noted, thanks.");
            return new Outcome(null, b.id());
        }
        if (!BookingTransitions.allowed(b.status(), target)) {
            reply(waId, waMessageId, b.hotelId(), b.id(), "This booking has already been settled ("
                    + b.status().name().toLowerCase().replace('_', ' ') + "). Nothing changed.");
            return new Outcome("booking was " + b.status(), b.id());
        }
        if (answer.arrived()) {
            bookingService.checkInByOwner(b.id());
            reply(waId, waMessageId, b.hotelId(), b.id(), "Thanks. " + b.guestName() + " is checked in.");
        } else {
            bookingService.noShowByOwner(b.id());
            reply(waId, waMessageId, b.hotelId(), b.id(), "Noted: " + b.guestName() + " didn't arrive, so you keep the ₹"
                    + WhatsAppMessages.rupees(b.depositAmount()) + " deposit. If they do turn up, send their code "
                    + b.code() + " here.");
        }
        return new Outcome(null, b.id());
    }

    /**
     * The owner typed 4 digits. If they match an active booking (confirmed, or marked no-show) at one of this
     * owner's hotels, the guest is checked in; otherwise the owner gets the help reply.
     */
    private Outcome deskCode(String waId, String waMessageId, List<OwnedHotel> owned, String code) {
        List<Booking> matches = bookings.withCode(owned.stream().map(OwnedHotel::id).toList(), code);
        if (matches.isEmpty()) {
            // Not a code for one of this owner's active bookings, so not a desk code at all.
            reply(waId, waMessageId, owned.getFirst().id(), null, HELP);
            return new Outcome("not understood", null);
        }
        Booking b = bookings.lock(matches.getFirst().id()).orElseThrow();
        if (!BookingTransitions.allowed(b.status(), BookingStatus.CHECKED_IN)) {
            reply(waId, waMessageId, b.hotelId(), b.id(), "That booking has already been settled.");
            return new Outcome("booking was " + b.status(), b.id());
        }
        bookingService.checkInByOwner(b.id());
        reply(waId, waMessageId, b.hotelId(), b.id(), "Code " + code + " is right: " + b.guestName() + ", "
                + b.guests() + (b.guests() == 1 ? " guest" : " guests") + ". Collect ₹"
                + WhatsAppMessages.rupees(b.payAtDesk()) + ". Checked in.");
        return new Outcome(null, b.id());
    }

    private static String tooLate(BookingStatus status, Action action, Booking b) {
        return switch (status) {
            case HOTEL_CONFIRMED, CHECKED_IN, COMPLETED -> action == Action.CONFIRM
                    ? "Already confirmed. The guest will show code " + b.code() + "."
                    : "This booking is already confirmed. To cancel it now, call LateStay support.";
            case HOTEL_DECLINED -> "This booking was already declined; the guest was refunded.";
            case HOTEL_TIMEOUT -> "This booking timed out after 5 minutes and the guest was refunded. Please don't "
                    + "hold the room.";
            case CANCELLED, REFUNDED -> "The guest cancelled this booking. Please don't hold the room.";
            default -> "This booking can't be answered any more.";
        };
    }

    private void reply(String waId, String inReplyTo, UUID hotelId, UUID bookingId, String text) {
        outbox.queue(hotelId, WhatsAppMessages.text(waId, text), bookingId, "reply:" + inReplyTo);
    }
}
