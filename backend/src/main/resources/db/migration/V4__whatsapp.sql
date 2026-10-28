-- The hotel-owner WhatsApp channel: every inbound and outbound message, plus a digits-only copy of each hotel's
-- number so inbound "from" numbers (digits, no +) match however the number was typed.

ALTER TABLE hotel ADD COLUMN whatsapp_wa_id text
    GENERATED ALWAYS AS (regexp_replace(whatsapp_number, '[^0-9]', '', 'g')) STORED;
CREATE INDEX hotel_whatsapp_wa_id_idx ON hotel (whatsapp_wa_id);

CREATE TABLE whatsapp_message (
    id            bigserial PRIMARY KEY,
    direction     text NOT NULL CHECK (direction IN ('IN', 'OUT')),
    -- NULL for messages from numbers that match no hotel (logged, then ignored).
    hotel_id      uuid REFERENCES hotel ON DELETE SET NULL,
    -- The owner's number, digits only (WhatsApp's wa_id).
    wa_id         text NOT NULL,
    -- WhatsApp's id ("wamid..."). Inbound: from the webhook, unique, so a redelivery is a no-op. Outbound: set once
    -- the Graph API accepts the message; status webhooks find the row by it.
    wa_message_id text UNIQUE,
    -- Inbound: text, button, interactive, ... Outbound: template or text.
    type          text NOT NULL,
    template      text,
    -- Inbound: the message object from the webhook. Outbound: the Graph API request body.
    payload       jsonb NOT NULL,
    -- Outbound: QUEUED -> SENT -> DELIVERED -> READ, or FAILED; NOT_SENT when WhatsApp isn't configured.
    -- Inbound: RECEIVED (handled) or IGNORED (unknown number or unparseable).
    status        text NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'NOT_SENT',
                      'RECEIVED', 'IGNORED')),
    error         text,
    attempts      int NOT NULL DEFAULT 0,
    booking_id    uuid REFERENCES booking ON DELETE SET NULL,
    -- Outbound only: stops a message being queued twice (e.g. one nightly ping per hotel per night).
    dedupe_key    text UNIQUE,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL
);
CREATE INDEX whatsapp_message_hotel_idx ON whatsapp_message (hotel_id, id DESC);
CREATE INDEX whatsapp_message_queued_idx ON whatsapp_message (id) WHERE status = 'QUEUED';
