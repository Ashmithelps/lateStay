package com.latestay.pricing;

import com.latestay.booking.BookingItSupport;
import org.springframework.test.context.TestPropertySource;

/** The booking context with occupancy, weekend and season pricing on (version 2), shared by its tests. */
@TestPropertySource(properties = "latestay.pricing.version=2")
public abstract class OccupancyPricingSupport extends BookingItSupport {
}
