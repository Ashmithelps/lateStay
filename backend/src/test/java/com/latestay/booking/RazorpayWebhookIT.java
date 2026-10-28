package com.latestay.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.payment.RazorpaySignatures;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Webhooks are signed with test_webhook_secret (src/test/resources/application.properties). */
class RazorpayWebhookIT extends BookingItSupport {

    static final double LAT = 28.0;
    static final String SECRET = "test_webhook_secret";

    static String captured(String orderId, String paymentId, long paise) {
        return """
                {"entity":"event","account_id":"acc_test","event":"payment.captured","contains":["payment"],\
                "payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":%d,"currency":"INR",\
                "status":"captured","order_id":"%s","method":"upi"}}},"created_at":1794416400}"""
                .formatted(paymentId, paise, orderId);
    }

    static String refundProcessed(String refundId, String paymentId, long paise) {
        return """
                {"entity":"event","account_id":"acc_test","event":"refund.processed","contains":["refund","payment"],\
                "payload":{"refund":{"entity":{"id":"%s","entity":"refund","amount":%d,"currency":"INR",\
                "payment_id":"%s","status":"processed"}}},"created_at":1794416700}"""
                .formatted(refundId, paise, paymentId);
    }

    ResultActions deliver(String body, String eventId) throws Exception {
        return deliver(body, eventId, RazorpaySignatures.hmac(SECRET, body.getBytes(StandardCharsets.UTF_8)));
    }

    ResultActions deliver(String body, String eventId, String signature) throws Exception {
        var request = post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)).header("X-Razorpay-Event-Id", eventId);
        if (signature != null) {
            request.header("X-Razorpay-Signature", signature);
        }
        return mvc.perform(request);
    }

    int deliveries(String eventId) {
        return jdbc.sql("SELECT count(*) FROM razorpay_webhook_event WHERE event_id = :id").param("id", eventId)
                .query(Integer.class).single();
    }

    static String eventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    BookingCreated booked() {
        return fixtures.book(fixtures.agreed(LAT, 1400, 700, 2));
    }

    @Test
    void badOrMissingSignaturesAreRejectedAndChangeNothing() throws Exception {
        BookingCreated created = booked();
        String body = captured(created.checkout().orderId(), "pay_sig_1", 20000);
        String event = eventId();

        deliver(body, event, RazorpaySignatures.hmac("not_the_secret", body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("invalid webhook signature"));
        deliver(body, event, null).andExpect(status().isBadRequest());
        String signature = RazorpaySignatures.hmac(SECRET, body.getBytes(StandardCharsets.UTF_8));
        deliver(body.replace("20000", "20001"), event, signature).andExpect(status().isBadRequest());

        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(deliveries(event)).isZero();
    }

    @Test
    void paymentCapturedMarksTheDepositPaidAndADuplicateDeliveryIsProcessedOnce() throws Exception {
        BookingCreated created = booked();
        String body = captured(created.checkout().orderId(), "pay_wh_1", 20000);
        String event = eventId();

        deliver(body, event).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        deliver(body, event).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        deliver(body, event).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.DEPOSIT_PAID);
        assertThat(events(created.bookingId()))
                .containsExactly("->PENDING_PAYMENT by TRAVELLER", "PENDING_PAYMENT->DEPOSIT_PAID by RAZORPAY");
        assertThat(deliveries(event)).isOne();
        assertThat(payment(created.bookingId())).containsEntry("status", "CAPTURED")
                .containsEntry("razorpay_payment_id", "pay_wh_1")
                .hasEntrySatisfying("raw", raw -> assertThat((String) raw).contains("\"order_id\""));
    }

    @Test
    void aSecondEventAboutTheSamePaymentChangesNothing() throws Exception {
        BookingCreated created = booked();
        String body = captured(created.checkout().orderId(), "pay_wh_2", 20000);

        deliver(body, eventId()).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        deliver(body, eventId()).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(events(created.bookingId())).hasSize(2);
    }

    @Test
    void theWebhookAfterCheckoutVerificationOnlyStoresTheEvent() throws Exception {
        BookingCreated created = booked();
        fixtures.pay(created);
        String paymentId = TestBookings.paymentIdFor(created);

        deliver(captured(created.checkout().orderId(), paymentId, 20000), eventId())
                .andExpect(status().isOk());
        assertThat(events(created.bookingId()))
                .containsExactly("->PENDING_PAYMENT by TRAVELLER", "PENDING_PAYMENT->DEPOSIT_PAID by TRAVELLER");
        assertThat(payment(created.bookingId()))
                .hasEntrySatisfying("raw", raw -> assertThat((String) raw).contains(paymentId));
    }

    @Test
    void aCaptureForTheWrongAmountOrAnUnknownOrderIsIgnored() throws Exception {
        BookingCreated created = booked();
        deliver(captured(created.checkout().orderId(), "pay_wh_3", 100), eventId()).andExpect(status().isOk());
        deliver(captured("order_unknown", "pay_wh_4", 20000), eventId()).andExpect(status().isOk());
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void refundProcessedFinishesTheRefundAndADuplicateIsProcessedOnce() throws Exception {
        BookingCreated created = booked();
        fixtures.pay(created);
        bookings.decline(created.bookingId());
        gateway.refundsProcessImmediately = false;
        jobs.issueRefunds();
        String refundId = (String) payment(created.bookingId()).get("refund_id");
        String body = refundProcessed(refundId, TestBookings.paymentIdFor(created), 20000);
        String event = eventId();

        deliver(body, event).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        deliver(body, event).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        deliver(body, eventId()).andExpect(jsonPath("$.outcome").value("PROCESSED"));

        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.REFUNDED);
        assertThat(events(created.bookingId())).filteredOn(e -> e.contains("->REFUNDED")).hasSize(1);
        assertThat(payment(created.bookingId())).containsEntry("status", "REFUNDED")
                .containsEntry("refund_id", refundId);
        jobs.issueRefunds();
        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isOne();
    }

    @Test
    void otherEventsAreAcknowledgedAndIgnored() throws Exception {
        String body = "{\"event\":\"payment.authorized\",\"payload\":{}}";
        deliver(body, eventId()).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED"));
    }

    @Test
    void deliveriesWithoutAnEventIdAreDeduplicatedByTheirBody() throws Exception {
        BookingCreated created = booked();
        String body = captured(created.checkout().orderId(), "pay_wh_5", 20000);
        String signature = RazorpaySignatures.hmac(SECRET, body.getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", signature))
                .andExpect(jsonPath("$.outcome").value("PROCESSED"));
        mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", signature))
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
    }
}
