package com.latestay.admin;

import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.ledger.Ledger;
import com.latestay.ledger.LedgerEntry;
import com.latestay.ledger.Settlement;
import com.latestay.ledger.Settlements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Weekly settlements to hotels: list, mark paid by UPI, CSV, manual adjustments. Amounts in paise. */
@RestController
@RequestMapping("/api/admin")
@Validated
class AdminPayoutController {

    record PaidBody(@NotBlank @Size(max = 64) String upiReference) {
    }

    record AdjustmentBody(@NotNull Long amountPaise, @NotBlank @Size(max = 200) String note) {
    }

    record SettlementDetail(Settlement settlement, List<Settlements.Line> lines) {
    }

    record HotelLedger(long balancePaise, List<LedgerEntry> entries) {
    }

    private final Settlements settlements;
    private final Ledger ledger;
    private final HotelRepository hotels;

    AdminPayoutController(Settlements settlements, Ledger ledger, HotelRepository hotels) {
        this.settlements = settlements;
        this.ledger = ledger;
        this.hotels = hotels;
    }

    @GetMapping("/settlements")
    List<Settlement> list(@RequestParam(required = false) String status) {
        return settlements.list(status == null || status.isBlank() ? null : status, AdminQueries.LIMIT);
    }

    @GetMapping("/settlements/{id}")
    SettlementDetail detail(@PathVariable UUID id) {
        Settlement s = settlements.find(id).orElseThrow(() -> new NotFoundException("settlement", id));
        return new SettlementDetail(s, settlements.lines(id));
    }

    @PostMapping("/settlements/{id}/paid")
    SettlementDetail paid(@PathVariable UUID id, @RequestBody @Valid PaidBody body) {
        settlements.markPaid(id, body.upiReference());
        return detail(id);
    }

    @GetMapping(value = "/settlements/{id}.csv", produces = "text/csv")
    ResponseEntity<String> csv(@PathVariable UUID id) {
        Settlement s = settlements.find(id).orElseThrow(() -> new NotFoundException("settlement", id));
        String file = "latestay-settlement-" + s.periodStart() + "-" + s.hotelId().toString().substring(0, 8) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .body(settlements.csv(id));
    }

    /** Make this week's settlements now (the job does it on Mondays at 10:00). */
    @PostMapping("/settlements/run")
    java.util.Map<String, Integer> run() {
        return java.util.Map.of("made", settlements.settleWeek());
    }

    @GetMapping("/hotels/{id}/ledger")
    HotelLedger hotelLedger(@PathVariable UUID id) {
        hotels.find(id).orElseThrow(() -> new NotFoundException("hotel", id));
        return new HotelLedger(ledger.balance(id), ledger.forHotel(id));
    }

    @PostMapping("/hotels/{id}/adjustments")
    LedgerEntry adjust(@PathVariable UUID id, @RequestBody @Valid AdjustmentBody body) {
        hotels.find(id).orElseThrow(() -> new NotFoundException("hotel", id));
        return ledger.adjust(id, body.amountPaise(), body.note().trim());
    }
}
