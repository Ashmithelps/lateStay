package com.latestay.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A weekly payout to one hotel. status DUE until an admin marks it paid with the UPI reference. */
public record Settlement(UUID id, UUID hotelId, String hotelName, LocalDate periodStart, LocalDate periodEnd,
        long amountPaise, String status, String upiReference, Instant paidAt, Instant createdAt) {
}
