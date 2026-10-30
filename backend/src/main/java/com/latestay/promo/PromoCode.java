package com.latestay.promo;

import java.time.Instant;
import java.util.UUID;

/** @param uses redemptions that count (reserved or used) */
public record PromoCode(UUID id, String code, long discountPaise, Instant validFrom, Instant validUntil, int maxUses,
        boolean active, int uses, Instant createdAt) {
}
