package com.latestay.phone;

import com.latestay.booking.IndianMobile;
import com.latestay.booking.InvalidBookingException;
import com.latestay.whatsapp.WhatsAppClient;
import com.latestay.whatsapp.WhatsAppMessages;
import com.latestay.whatsapp.WhatsAppProperties;
import com.latestay.whatsapp.WhatsAppSendException;
import com.latestay.web.Pii;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-time codes on WhatsApp (template phone_otp) before payment: 6 digits, valid 5 minutes, 3 attempts, limited
 * per phone here and per IP in the RateLimiter. Only hashes are stored. The code goes straight to the Graph API
 * (not through the WhatsApp outbox), so it is never written down in plain text.
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration RESEND_AFTER = Duration.ofSeconds(30);

    public record Started(UUID id, Instant expiresAt, long resendAfterSeconds, boolean sent) {
    }

    public enum Outcome { VERIFIED, WRONG_CODE, EXPIRED, LOCKED, UNKNOWN }

    /** @param phoneHash set when VERIFIED */
    public record Checked(Outcome outcome, int attemptsLeft, String phoneHash) {
    }

    /** Too many codes for one number. Maps to 429. */
    public static class TooManyCodesException extends RuntimeException {
        private final long retryAfterSeconds;

        TooManyCodesException(long retryAfterSeconds) {
            super("Too many codes for this number. Try again in " + Math.max(1, retryAfterSeconds / 60)
                    + " minutes.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** WhatsApp didn't accept the code. Maps to 502. */
    public static class CodeNotSentException extends RuntimeException {
        CodeNotSentException() {
            super("We couldn't send the code on WhatsApp. Check the number and try again.");
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PhoneHashes hashes;
    private final PhoneVerificationProperties properties;
    private final WhatsAppClient whatsApp;
    private final WhatsAppProperties whatsAppProperties;
    private final Clock clock;
    private final ObjectProvider<DevCodeSink> devCodes;

    public PhoneVerificationService(JdbcClient jdbc, TransactionTemplate tx, PhoneHashes hashes,
            PhoneVerificationProperties properties, WhatsAppClient whatsApp, WhatsAppProperties whatsAppProperties,
            Clock clock, ObjectProvider<DevCodeSink> devCodes) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.hashes = hashes;
        this.properties = properties;
        this.whatsApp = whatsApp;
        this.whatsAppProperties = whatsAppProperties;
        this.clock = clock;
        this.devCodes = devCodes;
    }

    /** Sends a new code to the number. */
    public Started start(String phoneInput, String ip) {
        String phone = IndianMobile.normalise(phoneInput)
                .orElseThrow(() -> new InvalidBookingException("enter a 10-digit Indian mobile number"));
        String phoneHash = hashes.phone(phone);
        Instant now = clock.instant();
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        UUID id = UUID.randomUUID();
        Instant expiresAt = now.plus(properties.codeValidity());

        tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:hash))").param("hash", phoneHash).query().listOfRows();
            checkLimits(phoneHash, now);
            jdbc.sql("""
                            INSERT INTO phone_verification (id, phone_hash, code_hash, ip_hash, expires_at, status,
                                   created_at)
                            VALUES (:id, :phone, :code, :ip, :expires, 'PENDING', :now)""")
                    .param("id", id).param("phone", phoneHash).param("code", hashes.code(id.toString(), code))
                    .param("ip", hashes.ip(ip == null ? "" : ip)).param("expires", at(expiresAt))
                    .param("now", at(now)).update();
        });

        boolean sent = false;
        if (whatsApp.canSend()) {
            try {
                whatsApp.send(WhatsAppMessages.phoneOtp(phone.substring(1), whatsAppProperties.language(),
                        properties.template(), code));
                sent = true;
            } catch (WhatsAppSendException e) {
                log.warn("Verification code to {} not sent: {}", Pii.mask(phone), Pii.scrub(e.getMessage()));
                throw new CodeNotSentException();
            }
        } else {
            log.warn("WhatsApp not configured: verification code for {} not sent", Pii.mask(phone));
        }
        devCodes.ifAvailable(sink -> sink.issued(phone, code));
        return new Started(id, expiresAt, RESEND_AFTER.toSeconds(), sent);
    }

    private void checkLimits(String phoneHash, Instant now) {
        Optional<Instant> oldestIn15 = window(phoneHash, now.minus(Duration.ofMinutes(15)),
                properties.sendsPerPhonePer15Minutes());
        if (oldestIn15.isPresent()) {
            throw new TooManyCodesException(Duration.between(now, oldestIn15.get().plus(Duration.ofMinutes(15)))
                    .toSeconds());
        }
        Optional<Instant> oldestInDay = window(phoneHash, now.minus(Duration.ofDays(1)), properties.sendsPerPhonePerDay());
        if (oldestInDay.isPresent()) {
            throw new TooManyCodesException(Duration.between(now, oldestInDay.get().plus(Duration.ofDays(1))).toSeconds());
        }
    }

    /** The oldest send in the window if the limit is already used up there. */
    private Optional<Instant> window(String phoneHash, Instant since, int limit) {
        var times = jdbc.sql("""
                        SELECT created_at FROM phone_verification WHERE phone_hash = :phone AND created_at > :since
                        ORDER BY created_at""")
                .param("phone", phoneHash).param("since", at(since))
                .query(OffsetDateTime.class).list();
        return times.size() >= limit ? Optional.of(times.getFirst().toInstant()) : Optional.empty();
    }

    /** Checks a code. Every wrong attempt is recorded (and the third locks the code), so this commits either way. */
    public Checked check(UUID id, String code) {
        return tx.execute(status -> {
            var row = jdbc.sql("""
                            SELECT phone_hash, code_hash, expires_at, attempts, status FROM phone_verification
                            WHERE id = :id FOR UPDATE""")
                    .param("id", id).query().listOfRows();
            if (row.isEmpty()) {
                return new Checked(Outcome.UNKNOWN, 0, null);
            }
            var r = row.getFirst();
            String state = (String) r.get("status");
            int attempts = (Integer) r.get("attempts");
            String phoneHash = (String) r.get("phone_hash");
            if ("VERIFIED".equals(state)) {
                return new Checked(Outcome.VERIFIED, 0, phoneHash);
            }
            if ("LOCKED".equals(state)) {
                return new Checked(Outcome.LOCKED, 0, null);
            }
            Instant now = clock.instant();
            Instant expires = ((java.sql.Timestamp) r.get("expires_at")).toInstant();
            if (!expires.isAfter(now)) {
                return new Checked(Outcome.EXPIRED, 0, null);
            }
            String given = code == null ? "" : code.replaceAll("\\s", "");
            if (PhoneHashes.same(hashes.code(id.toString(), given), (String) r.get("code_hash"))) {
                jdbc.sql("UPDATE phone_verification SET status = 'VERIFIED', verified_at = :now WHERE id = :id")
                        .param("id", id).param("now", at(now)).update();
                return new Checked(Outcome.VERIFIED, 0, phoneHash);
            }
            int used = attempts + 1;
            boolean locked = used >= properties.maxAttempts();
            jdbc.sql("UPDATE phone_verification SET attempts = :used, status = :status WHERE id = :id")
                    .param("id", id).param("used", used).param("status", locked ? "LOCKED" : "PENDING").update();
            return new Checked(locked ? Outcome.LOCKED : Outcome.WRONG_CODE, properties.maxAttempts() - used, null);
        });
    }

    /** Is this number the one verified on the device that sent {@code cookiePhoneHash}? */
    public boolean verifiedOnDevice(String phoneInput, String cookiePhoneHash) {
        return IndianMobile.normalise(phoneInput).map(hashes::phone)
                .map(h -> PhoneHashes.same(h, cookiePhoneHash)).orElse(false);
    }

    /** Privacy housekeeping: verification rows aren't needed after a day. */
    public int deleteOld() {
        return jdbc.sql("DELETE FROM phone_verification WHERE created_at < :cutoff")
                .param("cutoff", at(clock.instant().minus(Duration.ofDays(2)))).update();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
