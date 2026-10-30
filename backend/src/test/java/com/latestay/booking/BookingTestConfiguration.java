package com.latestay.booking;

import com.latestay.TestClock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A hand-moved clock, a recording payment gateway and WhatsApp client, and fake photo processing and storage,
 * shared by the integration tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BookingTestConfiguration {

    /** 22:30 IST on 11 Nov 2026. */
    public static final Instant START = Instant.parse("2026-11-11T17:00:00Z");

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock(START);
    }

    @Bean
    @Primary
    RecordingRazorpayGateway recordingRazorpayGateway() {
        return new RecordingRazorpayGateway();
    }

    @Bean
    @Primary
    com.latestay.whatsapp.RecordingWhatsAppClient recordingWhatsAppClient() {
        return new com.latestay.whatsapp.RecordingWhatsAppClient();
    }

    @Bean
    @Primary
    com.latestay.photo.FakePhotos.Processor fakeImageProcessor() {
        return new com.latestay.photo.FakePhotos.Processor();
    }

    @Bean
    @Primary
    com.latestay.photo.FakePhotos.Storage fakePhotoStorage() {
        return new com.latestay.photo.FakePhotos.Storage();
    }

    @Bean
    @Primary
    com.latestay.push.RecordingPushGateway recordingPushGateway() {
        return new com.latestay.push.RecordingPushGateway();
    }

    @Bean
    TestBookings testBookings(org.springframework.context.ApplicationContext context) {
        return new TestBookings(context);
    }

}
