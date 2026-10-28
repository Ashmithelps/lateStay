package com.latestay.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Razorpay's REST API (orders and refunds), with HTTP basic auth as key_id:key_secret. */
class HttpRazorpayGateway implements RazorpayGateway {

    private final RazorpayProperties properties;
    private final RestClient http;

    HttpRazorpayGateway(RazorpayProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.baseUrl(properties.baseUrl())
                .defaultHeaders(h -> h.setBasicAuth(properties.keyId(), properties.keySecret()))
                .build();
    }

    @Override
    public Mode mode() {
        return Mode.RAZORPAY;
    }

    @Override
    public String keyId() {
        return properties.keyId();
    }

    @Override
    public Order createOrder(long amount, String receipt) {
        JsonNode order = call(() -> http.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amount * 100, "currency", "INR", "receipt", receipt,
                        "notes", Map.of("booking_id", receipt)))
                .retrieve().body(JsonNode.class), "create order");
        return new Order(order.path("id").asText(), order.path("amount").asLong() / 100);
    }

    @Override
    public Refund refund(String paymentId, long amount, String receipt) {
        try {
            JsonNode refund = http.post().uri("/payments/{id}/refund", paymentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amount * 100, "speed", "normal", "receipt", receipt))
                    .retrieve().body(JsonNode.class);
            return toRefund(refund);
        } catch (RestClientResponseException e) {
            // A retry after a lost response: the payment is already refunded, so return that refund.
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(400))
                    && e.getResponseBodyAsString().toLowerCase().contains("fully refunded")) {
                JsonNode refunds = call(() -> http.get().uri("/payments/{id}/refunds", paymentId).retrieve()
                        .body(JsonNode.class), "fetch refunds");
                if (refunds.path("items").size() > 0) {
                    return toRefund(refunds.path("items").get(0));
                }
            }
            throw new PaymentGatewayException("Razorpay refund failed: " + e.getStatusCode() + " "
                    + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("Razorpay refund failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean checkoutSignatureValid(String orderId, String paymentId, String signature) {
        return RazorpaySignatures.checkoutValid(orderId, paymentId, signature, properties.keySecret());
    }

    private static Refund toRefund(JsonNode refund) {
        return new Refund(refund.path("id").asText(), "processed".equals(refund.path("status").asText()));
    }

    private static JsonNode call(java.util.function.Supplier<JsonNode> request, String what) {
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new PaymentGatewayException("Razorpay " + what + ": empty response");
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new PaymentGatewayException("Razorpay " + what + " failed: " + e.getStatusCode() + " "
                    + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("Razorpay " + what + " failed: " + e.getMessage(), e);
        }
    }
}
