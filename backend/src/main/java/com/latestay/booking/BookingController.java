package com.latestay.booking;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingDtos.BookingPreview;
import com.latestay.booking.BookingDtos.BookingView;
import com.latestay.booking.BookingDtos.CreateBookingRequest;
import com.latestay.booking.BookingDtos.VerifyPaymentRequest;
import com.latestay.phone.PhoneCookies;
import jakarta.servlet.http.HttpServletRequest;
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
    private final PhoneCookies phoneCookies;

    BookingController(BookingService bookings, BookingViews views, PhoneCookies phoneCookies) {
        this.bookings = bookings;
        this.views = views;
        this.phoneCookies = phoneCookies;
    }

    /** Needs this device's "phone verified" cookie for the same number (403 PHONE_NOT_VERIFIED otherwise). */
    @PostMapping("/api/bookings")
    BookingCreated create(@Valid @RequestBody CreateBookingRequest request, HttpServletRequest http) {
        return bookings.create(request, phoneCookies.verifiedPhoneHash(http).orElse(null));
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

    /** "I did arrive", after the hotel said the guest didn't. Once per booking. */
    @PostMapping("/api/bookings/{id}/arrived")
    BookingView arrived(@PathVariable UUID id) {
        bookings.travellerArrived(id);
        return views.view(id);
    }

    /** "The hotel turned me away", from the arrival time on. */
    @PostMapping("/api/bookings/{id}/turned-away")
    BookingView turnedAway(@PathVariable UUID id) {
        bookings.turnedAway(id);
        return views.view(id);
    }
}
