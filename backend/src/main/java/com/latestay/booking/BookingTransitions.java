package com.latestay.booking;

import static com.latestay.booking.BookingStatus.CANCELLED;
import static com.latestay.booking.BookingStatus.CHECKED_IN;
import static com.latestay.booking.BookingStatus.COMPLETED;
import static com.latestay.booking.BookingStatus.DEPOSIT_PAID;
import static com.latestay.booking.BookingStatus.DISPUTED;
import static com.latestay.booking.BookingStatus.EXPIRED;
import static com.latestay.booking.BookingStatus.HOTEL_CONFIRMED;
import static com.latestay.booking.BookingStatus.HOTEL_DECLINED;
import static com.latestay.booking.BookingStatus.HOTEL_FAILED;
import static com.latestay.booking.BookingStatus.HOTEL_TIMEOUT;
import static com.latestay.booking.BookingStatus.NO_SHOW;
import static com.latestay.booking.BookingStatus.PENDING_PAYMENT;
import static com.latestay.booking.BookingStatus.REFUNDED;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The booking state machine: every legal move, and a guard that rejects the rest. */
public final class BookingTransitions {

    private static final Map<BookingStatus, Set<BookingStatus>> LEGAL = new EnumMap<>(BookingStatus.class);

    static {
        for (BookingStatus status : BookingStatus.values()) {
            LEGAL.put(status, EnumSet.noneOf(BookingStatus.class));
        }
        allow(PENDING_PAYMENT, DEPOSIT_PAID, EXPIRED, CANCELLED);
        allow(DEPOSIT_PAID, HOTEL_CONFIRMED, HOTEL_DECLINED, HOTEL_TIMEOUT, CANCELLED, HOTEL_FAILED);
        allow(HOTEL_DECLINED, REFUNDED);
        allow(HOTEL_TIMEOUT, REFUNDED);
        allow(CANCELLED, REFUNDED);
        // Owner: Arrived / the code (CHECKED_IN), Didn't arrive (NO_SHOW). Job: nobody answered (COMPLETED).
        // Traveller: turned away (DISPUTED). Admin: Rescue (HOTEL_FAILED).
        allow(HOTEL_CONFIRMED, CHECKED_IN, NO_SHOW, COMPLETED, DISPUTED, HOTEL_FAILED);
        allow(CHECKED_IN, COMPLETED, HOTEL_FAILED);
        // Owner types the code after all (CHECKED_IN); traveller "I did arrive", once (DISPUTED).
        allow(NO_SHOW, CHECKED_IN, DISPUTED, HOTEL_FAILED);
        // Admin settles: guest stayed (COMPLETED), guest didn't arrive (NO_SHOW, final), Rescue (HOTEL_FAILED).
        allow(DISPUTED, COMPLETED, NO_SHOW, HOTEL_FAILED);
        // Admin Rescue the morning after, when nobody answered and the booking completed on its own.
        allow(COMPLETED, HOTEL_FAILED);
    }

    private BookingTransitions() {
    }

    private static void allow(BookingStatus from, BookingStatus... to) {
        LEGAL.get(from).addAll(Set.of(to));
    }

    public static boolean allowed(BookingStatus from, BookingStatus to) {
        return LEGAL.get(from).contains(to);
    }

    /** @throws IllegalTransitionException unless {@code from -> to} is in the table */
    public static void guard(BookingStatus from, BookingStatus to) {
        if (!allowed(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    public static Set<BookingStatus> from(BookingStatus status) {
        return Collections.unmodifiableSet(LEGAL.get(status));
    }
}
