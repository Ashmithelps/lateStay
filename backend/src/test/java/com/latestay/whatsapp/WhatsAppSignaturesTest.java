package com.latestay.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WhatsAppSignaturesTest {

    static final String SECRET = "test_wa_app_secret";
    static final byte[] BODY = """
            {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[]}]}"""
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void headerIsSha256PrefixPlusHexHmacOfTheRawBody() {
        // RFC 4231 test case 2, in Meta's header format.
        assertThat(WhatsAppSignatures.sign("what do ya want for nothing?".getBytes(StandardCharsets.UTF_8), "Jefe"))
                .isEqualTo("sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void acceptsTheRightSignature() {
        String header = WhatsAppSignatures.sign(BODY, SECRET);
        assertThat(WhatsAppSignatures.valid(BODY, header, SECRET)).isTrue();
        assertThat(WhatsAppSignatures.valid(BODY, header.toUpperCase().replace("SHA256=", "sha256="), SECRET)).isTrue();
    }

    @Test
    void rejectsTamperedBodiesWrongSecretsAndBadHeaders() {
        String header = WhatsAppSignatures.sign(BODY, SECRET);
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8).replace("\"1\"", "\"2\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(WhatsAppSignatures.valid(tampered, header, SECRET)).isFalse();
        assertThat(WhatsAppSignatures.valid(BODY, header, "another_secret")).isFalse();
        assertThat(WhatsAppSignatures.valid(BODY, header.substring("sha256=".length()), SECRET)).isFalse();
        assertThat(WhatsAppSignatures.valid(BODY, "sha1=" + header.substring(7), SECRET)).isFalse();
        assertThat(WhatsAppSignatures.valid(BODY, "sha256=", SECRET)).isFalse();
        assertThat(WhatsAppSignatures.valid(BODY, null, SECRET)).isFalse();
        assertThat(WhatsAppSignatures.valid(BODY, header, "")).isFalse();
        assertThat(WhatsAppSignatures.valid(null, header, SECRET)).isFalse();
    }
}
