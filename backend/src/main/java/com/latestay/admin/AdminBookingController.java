package com.latestay.admin;

import com.latestay.admin.AdminViews.AdminBooking;
import com.latestay.admin.AdminViews.AdminBookingDetail;
import com.latestay.booking.BookingRepository;
import com.latestay.booking.BookingService;
import com.latestay.hotel.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The bookings board: the hotel's confirm / decline / check-in, done by an admin until hotels have their own. */
@RestController
@RequestMapping("/api/admin")
@Validated
class AdminBookingController {

    record CheckInBody(@NotBlank String code) {
    }

    record SettleBody(@jakarta.validation.constraints.NotNull com.latestay.booking.DisputeOutcome outcome) {
    }

    private final BookingService bookings;
    private final BookingRepository repository;
    private final AdminQueries queries;

    AdminBookingController(BookingService bookings, BookingRepository repository, AdminQueries queries) {
        this.bookings = bookings;
        this.repository = repository;
        this.queries = queries;
    }

    @GetMapping("/me")
    Map<String, String> me(Principal principal) {
        return Map.of("username", principal.getName());
    }

    @GetMapping("/bookings")
    List<AdminBooking> bookings() {
        return queries.recentBookings();
    }

    @GetMapping("/bookings/{id}")
    AdminBookingDetail booking(@PathVariable UUID id) {
        return detail(id);
    }

    @PostMapping("/bookings/{id}/confirm")
    AdminBookingDetail confirm(@PathVariable UUID id) {
        bookings.confirm(id);
        return detail(id);
    }

    @PostMapping("/bookings/{id}/decline")
    AdminBookingDetail decline(@PathVariable UUID id) {
        bookings.decline(id);
        return detail(id);
    }

    @PostMapping("/bookings/{id}/check-in")
    AdminBookingDetail checkIn(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid CheckInBody body) {
        bookings.checkIn(id, body.code());
        return detail(id);
    }

    /** Settle a dispute: the guest stayed (COMPLETED) or didn't arrive (NO_SHOW, final). */
    @PostMapping("/bookings/{id}/settle")
    AdminBookingDetail settle(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid SettleBody body) {
        bookings.settle(id, body.outcome());
        return detail(id);
    }

    /** A "turned away" dispute keeps its hotel out of search until it is settled, not just DISPUTE_HIDE_HOURS. */
    @PostMapping("/bookings/{id}/keep-hidden")
    AdminBookingDetail keepHidden(@PathVariable UUID id) {
        bookings.keepHidden(id);
        return detail(id);
    }

    /** The hotel let the traveller down: HOTEL_FAILED, refund now, one failure on the hotel's record. */
    @PostMapping("/bookings/{id}/rescue")
    AdminBookingDetail rescue(@PathVariable UUID id) {
        bookings.rescue(id);
        return detail(id);
    }

    private AdminBookingDetail detail(UUID id) {
        AdminBooking booking = queries.booking(id).orElseThrow(() -> new NotFoundException("booking", id));
        return new AdminBookingDetail(booking, repository.events(id));
    }
}
