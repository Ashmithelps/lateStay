package com.latestay.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.TestHotels;
import com.latestay.phone.PhoneCookies;
import com.latestay.whatsapp.RecordingWhatsAppClient;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** WhatsApp codes before payment: expiry, attempts, per-phone limits, hashes only, and the device cookie. */
class PhoneVerificationIT extends BookingItSupport {

    @Autowired
    ObjectMapper json;
    @Autowired
    RecordingWhatsAppClient whatsApp;

    /** A number no other test uses, so the per-phone limits start fresh. */
    static String freshPhone() {
        return TestHotels.uniqueWhatsAppNumber();
    }

    static String e164(String phone) {
        return "+" + phone.replaceAll("[^0-9]", "");
    }

    ResultActions start(String phone) throws Exception {
        return mvc.perform(post("/api/phone-verifications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
    }

    record Sent(UUID id, String code) {
    }

    Sent send(String phone) throws Exception {
        whatsApp.reset();
        String body = start(phone).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode message = whatsApp.sent.getLast();
        assertThat(message.path("to").asText()).isEqualTo(e164(phone).substring(1));
        assertThat(message.path("template").path("name").asText()).isEqualTo("phone_otp");
        String code = message.path("template").path("components").get(0).path("parameters").get(0).path("text").asText();
        assertThat(message.path("template").path("components").get(1).path("sub_type").asText()).isEqualTo("url");
        assertThat(message.path("template").path("components").get(1).path("parameters").get(0).path("text").asText())
                .isEqualTo(code);
        return new Sent(UUID.fromString(json.readTree(body).path("id").asText()), code);
    }

    ResultActions check(UUID id, String code) throws Exception {
        return mvc.perform(post("/api/phone-verifications/{id}/check", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    @Test
    void theRightCodeVerifiesTheNumberOnThisDevice() throws Exception {
        String phone = freshPhone();
        Sent sent = send(phone);
        assertThat(sent.code()).matches("\\d{6}");

        MvcResult result = check(sent.id(), sent.code())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(cookie().exists(PhoneCookies.NAME))
                .andExpect(cookie().httpOnly(PhoneCookies.NAME, true))
                .andExpect(cookie().maxAge(PhoneCookies.NAME, (int) Duration.ofDays(30).toSeconds()))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(PhoneCookies.NAME);
        assertThat(cookie.getValue()).doesNotContain(e164(phone).substring(3));

        mvc.perform(get("/api/phone-verifications/status").param("phone", phone).cookie(cookie))
                .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(get("/api/phone-verifications/status").param("phone", freshPhone()).cookie(cookie))
                .andExpect(jsonPath("$.verified").value(false));
        mvc.perform(get("/api/phone-verifications/status").param("phone", phone))
                .andExpect(jsonPath("$.verified").value(false));
    }

    @Test
    void threeWrongCodesLockItAndEvenTheRightCodeIsThenRefused() throws Exception {
        Sent sent = send(freshPhone());
        check(sent.id(), wrong(sent.code())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_CODE")).andExpect(jsonPath("$.attemptsLeft").value(2));
        check(sent.id(), wrong(sent.code())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.attemptsLeft").value(1))
                .andExpect(jsonPath("$.detail").value("That code isn't right. One try left."));
        check(sent.id(), wrong(sent.code())).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOCKED"));
        check(sent.id(), sent.code()).andExpect(status().isTooManyRequests())
                .andExpect(cookie().doesNotExist(PhoneCookies.NAME));
    }

    @Test
    void aCodeExpiresAfterFiveMinutes() throws Exception {
        Sent sent = send(freshPhone());
        clock.advance(Duration.ofMinutes(5));
        check(sent.id(), sent.code()).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("EXPIRED"));

        Sent again = send(freshPhone());
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        check(again.id(), again.code()).andExpect(status().isOk());
    }

    @Test
    void aNumberGetsThreeCodesPerFifteenMinutes() throws Exception {
        String phone = freshPhone();
        for (int i = 0; i < 3; i++) {
            send(phone);
            clock.advance(Duration.ofMinutes(1));
        }
        start(phone).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_CODES"))
                .andExpect(header().exists("Retry-After"));
        clock.advance(Duration.ofMinutes(13));
        start(phone).andExpect(status().isCreated());
    }

    @Test
    void aNumberGetsTenCodesPerDay() throws Exception {
        String phone = freshPhone();
        for (int i = 0; i < 10; i++) {
            send(phone);
            clock.advance(Duration.ofMinutes(16));
        }
        start(phone).andExpect(status().isTooManyRequests());
    }

    @Test
    void onlyHashesAreStored() throws Exception {
        String phone = freshPhone();
        Sent sent = send(phone);
        String digits = e164(phone).substring(3);
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM phone_verification WHERE id = :id")
                .param("id", sent.id()).query().listOfRows();
        assertThat(rows.toString()).doesNotContain(digits).doesNotContain(sent.code());
        assertThat(jdbc.sql("SELECT count(*) FROM whatsapp_message WHERE payload::text LIKE :code")
                .param("code", "%" + sent.code() + "%").query(Integer.class).single()).isZero();
    }

    @Test
    void bookingNeedsTheCookieForTheSameNumber() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(29.5, 1400, 700, 2);
        String body = "{\"sessionId\":\"" + agreed.sessionId() + "\",\"guestName\":\"Asha Verma\","
                + "\"phone\":\"98765 43210\",\"idConsent\":true}";

        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PHONE_NOT_VERIFIED"));
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body)
                        .cookie(fixtures.phoneCookie("+919123456789")))
                .andExpect(status().isForbidden());
        Cookie good = fixtures.phoneCookie(TestBookings.PHONE);
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body)
                        .cookie(new Cookie(PhoneCookies.NAME, good.getValue().replace('a', 'b').replace('1', '2'))))
                .andExpect(status().isForbidden());

        clock.advance(Duration.ofDays(30));
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body).cookie(good))
                .andExpect(status().isForbidden());
        clock.set(BookingTestConfiguration.START);
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body).cookie(good))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }
}
