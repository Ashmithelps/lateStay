package com.latestay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RazorpaySignaturesTest {

    static final String KEY_SECRET = "test_key_secret";
    static final String WEBHOOK_SECRET = "test_webhook_secret";
    static final byte[] BODY = """
            {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_29QQoUBi66xm2f"}}}}"""
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void hmacMatchesAKnownVector() {
        // RFC 4231 test case 2.
        assertThat(RazorpaySignatures.hmac("Jefe", "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void checkoutSignatureIsOrderPipePayment() {
        String signature = RazorpaySignatures.hmac(KEY_SECRET,
                "order_9A33XWu170gUtm|pay_29QQoUBi66xm2f".getBytes(StandardCharsets.UTF_8));
        assertThat(RazorpaySignatures.checkoutValid("order_9A33XWu170gUtm", "pay_29QQoUBi66xm2f", signature,
                KEY_SECRET)).isTrue();
        assertThat(RazorpaySignatures.checkoutValid("order_9A33XWu170gUtm", "pay_29QQoUBi66xm2f",
                signature.toUpperCase(), KEY_SECRET)).isTrue();
    }

    @Test
    void checkoutSignatureFailsForAnotherPaymentOrderOrSecret() {
        String signature = RazorpaySignatures.hmac(KEY_SECRET, "order_A|pay_B".getBytes(StandardCharsets.UTF_8));
        assertThat(RazorpaySignatures.checkoutValid("order_A", "pay_C", signature, KEY_SECRET)).isFalse();
        assertThat(RazorpaySignatures.checkoutValid("order_X", "pay_B", signature, KEY_SECRET)).isFalse();
        assertThat(RazorpaySignatures.checkoutValid("order_A", "pay_B", signature, "another_secret")).isFalse();
        assertThat(RazorpaySignatures.checkoutValid("order_A", "pay_B", "", KEY_SECRET)).isFalse();
        assertThat(RazorpaySignatures.checkoutValid("order_A", "pay_B", null, KEY_SECRET)).isFalse();
        assertThat(RazorpaySignatures.checkoutValid("order_A", "pay_B", "zz", KEY_SECRET)).isFalse();
        assertThat(RazorpaySignatures.checkoutValid(null, "pay_B", signature, KEY_SECRET)).isFalse();
    }

    @Test
    void webhookSignatureCoversTheExactBody() {
        String signature = RazorpaySignatures.hmac(WEBHOOK_SECRET, BODY);
        assertThat(RazorpaySignatures.webhookValid(BODY, signature, WEBHOOK_SECRET)).isTrue();

        byte[] tampered = new String(BODY, StandardCharsets.UTF_8).replace("29QQ", "29QR")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(RazorpaySignatures.webhookValid(tampered, signature, WEBHOOK_SECRET)).isFalse();
        byte[] reformatted = (new String(BODY, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThat(RazorpaySignatures.webhookValid(reformatted, signature, WEBHOOK_SECRET)).isFalse();
        assertThat(RazorpaySignatures.webhookValid(BODY, signature, KEY_SECRET)).isFalse();
        assertThat(RazorpaySignatures.webhookValid(BODY, null, WEBHOOK_SECRET)).isFalse();
        assertThat(RazorpaySignatures.webhookValid(null, signature, WEBHOOK_SECRET)).isFalse();
    }

    @Test
    void refusesToSignWithoutASecret() {
        assertThatThrownBy(() -> RazorpaySignatures.hmac("", BODY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fakeGatewaySignsLikeCheckout() {
        FakeRazorpayGateway fake = new FakeRazorpayGateway();
        String signature = FakeRazorpayGateway.sign("order_fake_1", "pay_fake_1");
        assertThat(fake.checkoutSignatureValid("order_fake_1", "pay_fake_1", signature)).isTrue();
        assertThat(fake.checkoutSignatureValid("order_fake_1", "pay_fake_2", signature)).isFalse();
    }
}
