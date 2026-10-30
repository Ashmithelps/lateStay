package com.latestay.phone;

import com.latestay.phone.PhoneVerificationService.Checked;
import com.latestay.phone.PhoneVerificationService.Started;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Verify the traveller's mobile number with a WhatsApp code before payment. */
@RestController
class PhoneVerificationController {

    record StartRequest(@NotBlank @Size(max = 20) String phone) {
    }

    record CheckRequest(@NotBlank @Size(max = 12) String code) {
    }

    record CheckResponse(boolean verified) {
    }

    record DeviceStatus(boolean verified) {
    }

    private final PhoneVerificationService verifications;
    private final PhoneCookies cookies;

    PhoneVerificationController(PhoneVerificationService verifications, PhoneCookies cookies) {
        this.verifications = verifications;
        this.cookies = cookies;
    }

    @PostMapping("/api/phone-verifications")
    ResponseEntity<Started> start(@Valid @RequestBody StartRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(verifications.start(body.phone(), request.getRemoteAddr()));
    }

    /**
     * 200 with a "verified on this device" cookie; otherwise a problem with {@code code}: WRONG_CODE (400, with
     * attemptsLeft), EXPIRED (410), LOCKED (429, ask for a new code) or UNKNOWN (404).
     */
    @PostMapping("/api/phone-verifications/{id}/check")
    ResponseEntity<?> check(@PathVariable UUID id, @Valid @RequestBody CheckRequest body) {
        Checked result = verifications.check(id, body.code());
        return switch (result.outcome()) {
            case VERIFIED -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookies.issue(result.phoneHash()).toString())
                    .body(new CheckResponse(true));
            case WRONG_CODE -> problem(HttpStatus.BAD_REQUEST, "WRONG_CODE",
                    result.attemptsLeft() == 1 ? "That code isn't right. One try left." : "That code isn't right.",
                    result.attemptsLeft());
            case EXPIRED -> problem(HttpStatus.GONE, "EXPIRED", "That code has expired. Ask for a new one.", 0);
            case LOCKED -> problem(HttpStatus.TOO_MANY_REQUESTS, "LOCKED",
                    "Too many wrong tries. Ask for a new code.", 0);
            case UNKNOWN -> problem(HttpStatus.NOT_FOUND, "UNKNOWN", "Ask for a new code.", 0);
        };
    }

    /** Whether this device already verified the number (so the booking page can skip the code step). */
    @GetMapping("/api/phone-verifications/status")
    DeviceStatus status(@RequestParam String phone, HttpServletRequest request) {
        return new DeviceStatus(cookies.verifiedPhoneHash(request)
                .map(hash -> verifications.verifiedOnDevice(phone, hash)).orElse(false));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, int left) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("attemptsLeft", left);
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(PhoneVerificationService.TooManyCodesException.class)
    ResponseEntity<ProblemDetail> tooMany(PhoneVerificationService.TooManyCodesException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setProperty("code", "TOO_MANY_CODES");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds())).body(problem);
    }

    @ExceptionHandler(PhoneVerificationService.CodeNotSentException.class)
    ProblemDetail notSent(PhoneVerificationService.CodeNotSentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
