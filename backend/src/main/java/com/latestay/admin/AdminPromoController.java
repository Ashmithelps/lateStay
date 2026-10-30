package com.latestay.admin;

import com.latestay.promo.PromoCode;
import com.latestay.promo.Promos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Promo codes: create, list (with uses), deactivate. Amounts in paise, whole rupees only. */
@RestController
@RequestMapping("/api/admin/promo-codes")
class AdminPromoController {

    record PromoBody(@NotBlank String code, @NotNull @Positive Long discountPaise, @NotNull Instant validFrom,
            @NotNull Instant validUntil, @NotNull @Positive Integer maxUses) {
    }

    private final Promos promos;

    AdminPromoController(Promos promos) {
        this.promos = promos;
    }

    @GetMapping
    List<PromoCode> list() {
        return promos.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PromoCode create(@RequestBody @Valid PromoBody body) {
        return promos.create(body.code(), body.discountPaise(), body.validFrom(), body.validUntil(), body.maxUses());
    }

    @PostMapping("/{id}/deactivate")
    PromoCode deactivate(@PathVariable UUID id) {
        return promos.deactivate(id);
    }
}
