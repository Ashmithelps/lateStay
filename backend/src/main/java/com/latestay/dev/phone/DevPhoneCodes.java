package com.latestay.dev.phone;

import com.latestay.booking.IndianMobile;
import com.latestay.phone.DevCodeSink;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local profile only: the last verification code sent to a number, kept in memory, so development and the
 * screenshot run can verify without WhatsApp. Production has no such endpoint (and no plain codes anywhere).
 */
@Profile("local")
@RestController
class DevPhoneCodes implements DevCodeSink {

    private final Map<String, String> latest = new ConcurrentHashMap<>();

    @Override
    public void issued(String phone, String code) {
        latest.put(phone, code);
    }

    @GetMapping("/api/dev/phone-verifications/latest")
    Map<String, String> latest(@RequestParam String phone) {
        String e164 = IndianMobile.normalise(phone)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a mobile number"));
        String code = latest.get(e164);
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no code sent to that number");
        }
        return Map.of("code", code);
    }
}
