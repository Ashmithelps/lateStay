package com.latestay.push;

import com.latestay.phone.PhoneCookies;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The app registers its FCM token after the traveller allows notifications (on a paid booking's pass). */
@RestController
class DeviceController {

    /**
     * @param token the Firebase Cloud Messaging registration token
     * @param bookingId the booking whose pass asked; the device also hears about later bookings from the same
     *     verified number
     */
    record DeviceBody(@NotNull @Pattern(regexp = "[A-Za-z0-9_:\\-.]{20,4096}") String token,
            @NotNull PushPlatform platform, @Schema(nullable = true) UUID bookingId) {
    }

    private final Devices devices;
    private final PhoneCookies phoneCookies;

    DeviceController(Devices devices, PhoneCookies phoneCookies) {
        this.devices = devices;
        this.phoneCookies = phoneCookies;
    }

    @PostMapping("/api/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@Valid @RequestBody DeviceBody body, HttpServletRequest http) {
        devices.register(body.token(), body.platform(), body.bookingId(),
                phoneCookies.verifiedPhoneHash(http).orElse(null));
    }
}
