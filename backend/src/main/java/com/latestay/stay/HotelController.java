package com.latestay.stay;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HotelController {

    private final HotelDetailService hotels;

    HotelController(HotelDetailService hotels) {
        this.hotels = hotels;
    }

    /** A hotel page; pass the stayRequestId from a search to get tonight's prices and distance. */
    @GetMapping("/api/hotels/{slug}")
    HotelDetail hotel(@PathVariable String slug, @RequestParam(required = false) UUID stayRequestId) {
        return hotels.detail(slug, stayRequestId);
    }
}
