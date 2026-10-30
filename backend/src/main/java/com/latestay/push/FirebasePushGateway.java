package com.latestay.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Firebase Cloud Messaging (HTTP v1 through the Admin SDK), for both iOS (via APNs) and Android. */
class FirebasePushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(FirebasePushGateway.class);
    /** Codes that mean this token will never work again. */
    private static final Set<MessagingErrorCode> GONE = Set.of(MessagingErrorCode.UNREGISTERED,
            MessagingErrorCode.SENDER_ID_MISMATCH, MessagingErrorCode.INVALID_ARGUMENT);

    private final FirebaseMessaging messaging;

    FirebasePushGateway(String serviceAccountJson) {
        try {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(
                            new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().stream().filter(a -> a.getName().equals("latestay")).findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(options, "latestay"));
            this.messaging = FirebaseMessaging.getInstance(app);
        } catch (IOException e) {
            throw new IllegalStateException("FIREBASE_SERVICE_ACCOUNT_JSON isn't a service account key", e);
        }
    }

    @Override
    public boolean canSend() {
        return true;
    }

    @Override
    public Result send(String token, PushPayload payload) {
        try {
            messaging.send(message(token, payload));
            return Result.SENT;
        } catch (FirebaseMessagingException e) {
            if (e.getMessagingErrorCode() != null && GONE.contains(e.getMessagingErrorCode())) {
                return Result.INVALID_TOKEN;
            }
            log.warn("FCM send failed: {} {}", e.getMessagingErrorCode(), e.getMessage());
            return Result.FAILED;
        }
    }

    static Message message(String token, PushPayload payload) {
        return Message.builder()
                .setToken(token)
                .setNotification(Notification.builder().setTitle(payload.title()).setBody(payload.body()).build())
                .putAllData(payload.data())
                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setChannelId(PushPayload.ANDROID_CHANNEL).build())
                        .build())
                .setApnsConfig(ApnsConfig.builder().setAps(Aps.builder().setSound("default").build()).build())
                .build();
    }
}
