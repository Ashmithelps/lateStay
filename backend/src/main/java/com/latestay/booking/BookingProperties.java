package com.latestay.booking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Money and timeouts for bookings ({@code latestay.booking.*}).
 *
 * @param depositCap the most a deposit can be; smaller agreed prices are paid in full online
 * @param commission LateStay's cut, taken out of the deposit
 * @param paymentWindow how long an unpaid booking holds its room
 * @param confirmWindow how long the hotel has to confirm after payment
 * @param arrivalGrace how long after the stated arrival the hotel holds the room (capped at the slot's window)
 * @param noShowAfter how long after arrive_by an unarrived guest is a no-show
 * @param phoneRetention how long after the stay the traveller's phone number is kept (privacy policy: 90 days)
 */
@ConfigurationProperties("latestay.booking")
public record BookingProperties(
        @DefaultValue("200") long depositCap,
        @DefaultValue("100") long commission,
        @DefaultValue("10m") Duration paymentWindow,
        @DefaultValue("5m") Duration confirmWindow,
        @DefaultValue("60m") Duration arrivalGrace,
        @DefaultValue("60m") Duration noShowAfter,
        @DefaultValue("90d") Duration phoneRetention) {
}
