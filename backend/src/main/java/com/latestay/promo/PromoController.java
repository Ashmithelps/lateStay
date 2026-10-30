package com.latestay.promo;

import com.latestay.booking.BookingViews;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** "Apply" on the Book screen: what a code takes off this negotiation's deposit (the phone is checked at booking). */
@RestController
@Validated
class PromoController {

    /** @param discount rupees off the deposit; payNow what's left to pay online (at least ₹1) */
    record PromoPreview(String code, long discount, long payNow) {
    }

    private final Promos promos;
    private final BookingViews views;

    PromoController(Promos promos, BookingViews views) {
        this.promos = promos;
        this.views = views;
    }

    @GetMapping("/api/negotiations/{sessionId}/promo")
    PromoPreview preview(@PathVariable UUID sessionId, @RequestParam @NotBlank @Size(max = 40) String code) {
        long deposit = views.preview(sessionId).depositAmount();
        Promos.Applied applied = promos.preview(code, deposit);
        return new PromoPreview(applied.promo().code(), applied.discountRupees(), applied.payNowRupees());
    }
}
