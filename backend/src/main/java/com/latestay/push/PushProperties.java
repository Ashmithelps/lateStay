package com.latestay.push;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Push notifications ({@code latestay.push.*}).
 *
 * @param firebaseServiceAccountJson the Firebase service account key, as JSON (FIREBASE_SERVICE_ACCOUNT_JSON); empty =
 *     notifications are queued and marked NOT_SENT
 * @param maxAttempts tries per message before it is FAILED
 * @param tokenRetention device tokens not seen for this long are deleted
 */
@ConfigurationProperties("latestay.push")
public record PushProperties(
        @DefaultValue("") String firebaseServiceAccountJson,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("90d") Duration tokenRetention) {
}
