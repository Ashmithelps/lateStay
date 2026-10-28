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

    OwnerMessageHandler(AvailabilityRepository availability, BookingService bookingService,
            BookingRepository bookings, WhatsAppOutbox outbox, AvailabilityPings pings) {
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
            case OwnerReply.Unknown unknown -> {
                reply(waId, waMessageId, owned.getFirst().id(), null, HELP);
                yield new Outcome("not understood", null);
            }
        };
    }

    private Outcome roomsFree(String waId, String waMessageId, List<OwnedHotel> owned, RoomsFree rooms) {
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
