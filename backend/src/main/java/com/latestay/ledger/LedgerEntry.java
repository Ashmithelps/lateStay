package com.latestay.ledger;

import java.time.Instant;
import java.util.UUID;

/** One row of what LateStay owes a hotel. Amounts in paise, signed: positive is owed to the hotel. */
public record LedgerEntry(UUID id, UUID hotelId, UUID bookingId, UUID settlementId, LedgerEntryType type,
        long amountPaise, String note, Instant createdAt) {
}
