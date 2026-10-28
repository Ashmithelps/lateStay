package com.latestay.admin;

import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.whatsapp.AvailabilityPings;
import com.latestay.whatsapp.WhatsAppLogEntry;
import com.latestay.whatsapp.WhatsAppMessageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A hotel's WhatsApp conversation with LateStay, and a manual availability ping. */
@RestController
@RequestMapping("/api/admin/hotels/{hotelId}/whatsapp")
class AdminWhatsAppController {

    private final WhatsAppMessageRepository messages;
    private final AvailabilityPings pings;
    private final HotelRepository hotels;

    AdminWhatsAppController(WhatsAppMessageRepository messages, AvailabilityPings pings, HotelRepository hotels) {
        this.messages = messages;
        this.pings = pings;
        this.hotels = hotels;
    }

    @GetMapping
    List<WhatsAppLogEntry> log(@PathVariable UUID hotelId, @RequestParam(defaultValue = "50") int limit) {
        hotels.find(hotelId).orElseThrow(() -> new NotFoundException("hotel", hotelId));
        return messages.forHotel(hotelId, Math.min(Math.max(limit, 1), 200)).stream().map(WhatsAppLogEntry::of)
                .toList();
    }

    /** Queues tonight's nightly_availability template now, even if the 21:00 ping already went out. */
    @PostMapping("/availability-ping")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void ping(@PathVariable UUID hotelId) {
        pings.pingNow(hotelId);
    }
}
