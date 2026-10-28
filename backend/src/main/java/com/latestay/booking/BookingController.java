package com.latestay.booking;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingDtos.BookingPreview;
import com.latestay.booking.BookingDtos.BookingView;
import com.latestay.booking.BookingDtos.CreateBookingRequest;
import com.latestay.booking.BookingDtos.VerifyPaymentRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The traveller's booking. No login for the MVP: the booking's uuid is the link to it. */
@RestController
class BookingController {

    private final BookingService bookings;
    private final BookingViews views;

    BookingController(BookingService bookings, BookingViews views) {
        this.bookings = bookings;
        this.views = views;
    }

    @PostMapping("/api/bookings")
    BookingCreated create(@Valid @RequestBody CreateBookingRequest request) {
        return bookings.create(request);
    }

    @GetMapping("/api/negotiations/{sessionId}/booking-preview")
    BookingPreview preview(@PathVariable UUID sessionId) {
        return views.preview(sessionId);
    }

    @GetMapping("/api/bookings/{id}")
    BookingView booking(@PathVariable UUID id) {
        return views.view(id);
    }

    @PostMapping("/api/bookings/{id}/verify")
    BookingView verify(@PathVariable UUID id, @Valid @RequestBody VerifyPaymentRequest request) {
        bookings.verify(id, request.orderId(), request.paymentId(), request.signature());
        return views.view(id);
    }

    @PostMapping("/api/bookings/{id}/cancel")
    BookingView cancel(@PathVariable UUID id) {
        bookings.cancel(id);
        return views.view(id);
    }
}
