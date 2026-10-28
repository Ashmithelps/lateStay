package com.latestay.booking;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One logged status change. {@code from} is null for the booking's creation. */
public record BookingEvent(@Schema(nullable = true) BookingStatus from, BookingStatus to, Actor actor, Instant at) {
}
