-- Push notifications for the iOS and Android apps (Firebase Cloud Messaging).

-- An app install's FCM token. phone_hash (from the "number verified on this device" cookie) lets later bookings from
-- the same number reach this device too. Tokens not seen for 90 days, or reported invalid by FCM, are deleted.
CREATE TABLE device_token (
    id           uuid PRIMARY KEY,
    token        text NOT NULL UNIQUE CHECK (length(token) BETWEEN 20 AND 4096),
    platform     text NOT NULL CHECK (platform IN ('IOS', 'ANDROID')),
    phone_hash   text,
    created_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL
);
CREATE INDEX device_token_phone_idx ON device_token (phone_hash) WHERE phone_hash IS NOT NULL;

-- The bookings a device asked to hear about (registered from that booking's pass).
CREATE TABLE device_booking (
    device_id  uuid NOT NULL REFERENCES device_token ON DELETE CASCADE,
    booking_id uuid NOT NULL REFERENCES booking ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (device_id, booking_id)
);
CREATE INDEX device_booking_booking_idx ON device_booking (booking_id);

-- Outbox: queued in the booking's transaction, sent by PushSender. One per booking and kind (dedupe_key), so a
-- replayed status change never notifies twice. Never a phone number, a desk code or a guest's name.
CREATE TABLE push_message (
    id         bigserial PRIMARY KEY,
    booking_id uuid NOT NULL REFERENCES booking ON DELETE CASCADE,
    kind       text NOT NULL,
    dedupe_key text NOT NULL UNIQUE,
    body       text NOT NULL CHECK (length(body) BETWEEN 1 AND 200),
    path       text NOT NULL CHECK (path ~ '^/pass/[0-9a-f-]{36}(/review)?$'),
    -- QUEUED -> SENT (to at least one device), NOT_SENT (no devices or push not configured), FAILED (gave up)
    status     text NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'NOT_SENT', 'FAILED')),
    attempts   int NOT NULL DEFAULT 0,
    error      text,
    created_at timestamptz NOT NULL,
    sent_at    timestamptz
);
CREATE INDEX push_message_queued_idx ON push_message (id) WHERE status = 'QUEUED';
