package com.latestay.analytics;

import com.latestay.booking.Booking;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.BookingStatusChanged;
import com.latestay.hotel.HotelRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The funnel events only the backend can vouch for: paid, confirmed, checked in, cancelled, turned away, rescued.
 * Recorded in the booking's transaction, under the traveller's analytics id from their search.
 */
@Component
class BookingAnalytics {

    private final Analytics analytics;
    private final HotelRepository hotels;
    private final JdbcClient jdbc;

    BookingAnalytics(Analytics analytics, HotelRepository hotels, JdbcClient jdbc) {
        this.analytics = analytics;
        this.hotels = hotels;
        this.jdbc = jdbc;
    }

    @EventListener
    void on(BookingStatusChanged change) {
        Booking b = change.booking();
        AnalyticsEventName event = switch (change.to()) {
            case DEPOSIT_PAID -> AnalyticsEventName.PAYMENT_SUCCEEDED;
            case HOTEL_CONFIRMED -> AnalyticsEventName.HOTEL_CONFIRMED;
            case CHECKED_IN -> AnalyticsEventName.CHECKED_IN;
            case CANCELLED -> AnalyticsEventName.CANCELLED;
            case DISPUTED -> Booking.TURNED_AWAY.equals(b.disputeReason()) ? AnalyticsEventName.TURNED_AWAY : null;
            case HOTEL_FAILED -> AnalyticsEventName.RESCUED;
            default -> null;
        };
        if (event == null) {
            return;
        }
        UUID stay = jdbc.sql("SELECT stay_request_id FROM negotiation_session WHERE id = :id")
                .param("id", b.sessionId()).query(UUID.class).single();
        Map<String, Object> props = new HashMap<>();
        props.put("hotel", hotels.find(b.hotelId()).map(h -> h.slug()).orElse(null));
        if (event == AnalyticsEventName.PAYMENT_SUCCEEDED) {
            props.put("price_band", AnalyticsSanitizer.priceBand(b.agreedPrice()));
            props.put("promo", promoUsed(b.id()));
        }
        if (event == AnalyticsEventName.CANCELLED) {
            props.put("stage", change.from() == BookingStatus.DEPOSIT_PAID ? "after_payment" : "before_payment");
        }
        analytics.record(event, analytics.distinctIdFor(stay), stay, null, props);
    }

    private boolean promoUsed(UUID bookingId) {
        return jdbc.sql("SELECT promo_discount_paise > 0 FROM booking WHERE id = :id").param("id", bookingId)
                .query(Boolean.class).optional().orElse(false);
    }
}
