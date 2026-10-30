-- Traveller WhatsApp updates, dispute hiding limits, the owner ledger and settlements, owner sign-in, photo uploads.
-- New money columns are in paise (bigint); existing rupee columns are unchanged.

ALTER TABLE booking
    -- The traveller ticked "Send my booking pass and updates on WhatsApp" (null = they didn't).
    ADD COLUMN whatsapp_updates_at timestamptz,
    -- "The hotel turned me away": the hotel is hidden from search for that night until then...
    ADD COLUMN hide_until timestamptz,
    -- ...or for as long as the dispute stays open, once an admin pressed "Keep hidden".
    ADD COLUMN keep_hidden_at timestamptz;

-- Messages to travellers (booking_confirmed, booking_update) go through the same outbox; their number is redacted
-- with the booking's phone, 90 days after the stay.
ALTER TABLE whatsapp_message
    ADD COLUMN audience text NOT NULL DEFAULT 'OWNER' CHECK (audience IN ('OWNER', 'TRAVELLER', 'ADMIN'));

-- Owners can set tonight's rooms on their owner page too.
ALTER TABLE nightly_availability DROP CONSTRAINT nightly_availability_source_check;
ALTER TABLE nightly_availability ADD CONSTRAINT nightly_availability_source_check
    CHECK (source IN ('WHATSAPP', 'ADMIN', 'OWNER_PAGE'));

/*
 * As in V6, except that a "turned away" dispute hides the hotel only until hide_until (DISPUTE_HIDE_HOURS after
 * the dispute), unless an admin pressed "Keep hidden".
 */
CREATE OR REPLACE FUNCTION rooms_available(p_hotel uuid, p_night date, p_now timestamptz) RETURNS int
LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM booking d
                     WHERE d.hotel_id = p_hotel AND d.night_of = p_night
                       AND d.status = 'DISPUTED' AND d.dispute_reason = 'TURNED_AWAY'
                       AND (d.keep_hidden_at IS NOT NULL OR d.hide_until > p_now)) THEN 0
        ELSE COALESCE((
            SELECT GREATEST(0, a.rooms_free
                - (SELECT count(*) FROM room_hold h
                   WHERE h.hotel_id = p_hotel AND h.night_of = p_night AND h.status = 'ACTIVE'
                     AND h.expires_at > p_now)
                - (SELECT count(*) FROM booking b
                   WHERE b.hotel_id = p_hotel AND b.night_of = p_night AND b.created_at > a.reported_at
                     AND b.status IN ('PENDING_PAYMENT', 'DEPOSIT_PAID', 'HOTEL_CONFIRMED', 'CHECKED_IN')))
            FROM nightly_availability a WHERE a.hotel_id = p_hotel AND a.night_of = p_night), 0)
    END
$$;

-- A weekly payout to one hotel: the ledger entries it covers are in settlement_line.
CREATE TABLE settlement (
    id           uuid PRIMARY KEY,
    hotel_id     uuid NOT NULL REFERENCES hotel,
    -- Monday to Sunday (Asia/Kolkata) before the Monday the job ran. Older entries that waited (a dispute that
    -- was open, a week that came to zero or less) are included too.
    period_start date NOT NULL,
    period_end   date NOT NULL,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    status       text NOT NULL CHECK (status IN ('DUE', 'PAID')),
    upi_reference text,
    paid_at      timestamptz,
    created_at   timestamptz NOT NULL,
    UNIQUE (hotel_id, period_start),
    CHECK ((status = 'PAID') = (upi_reference IS NOT NULL AND paid_at IS NOT NULL))
);

/*
 * What LateStay owes each hotel, append-only. Balance owed = sum(amount_paise). A booking the guest stayed at (or
 * didn't turn up for) adds DEPOSIT_COLLECTED (+deposit) and COMMISSION (-commission); a refund after that adds
 * REFUND (-(deposit - commission)); paying a settlement adds PAYOUT (-amount).
 */
CREATE TABLE ledger_entry (
    id            uuid PRIMARY KEY,
    hotel_id      uuid NOT NULL REFERENCES hotel,
    booking_id    uuid REFERENCES booking,
    settlement_id uuid REFERENCES settlement,   -- PAYOUT only: the settlement it paid
    type          text NOT NULL CHECK (type IN ('DEPOSIT_COLLECTED', 'COMMISSION', 'REFUND', 'PAYOUT', 'ADJUSTMENT')),
    amount_paise  bigint NOT NULL,
    note          text,
    created_at    timestamptz NOT NULL,
    CHECK ((type = 'PAYOUT') = (settlement_id IS NOT NULL)),
    CHECK (type IN ('PAYOUT', 'ADJUSTMENT') OR booking_id IS NOT NULL)
);
-- The booking rules write each of these at most once per booking, so a replayed transition can't double-count.
CREATE UNIQUE INDEX ledger_entry_once_idx ON ledger_entry (booking_id, type)
    WHERE type IN ('DEPOSIT_COLLECTED', 'COMMISSION', 'REFUND');
CREATE UNIQUE INDEX ledger_entry_payout_idx ON ledger_entry (settlement_id) WHERE type = 'PAYOUT';
CREATE INDEX ledger_entry_hotel_idx ON ledger_entry (hotel_id, created_at);

CREATE FUNCTION ledger_entry_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only; add an ADJUSTMENT instead';
END
$$;
CREATE TRIGGER ledger_entry_append_only BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_append_only();

-- Which entries a settlement covers (kept apart so the ledger itself is never updated). PAYOUT entries are never
-- lines.
CREATE TABLE settlement_line (
    settlement_id   uuid NOT NULL REFERENCES settlement,
    ledger_entry_id uuid NOT NULL UNIQUE REFERENCES ledger_entry,
    PRIMARY KEY (settlement_id, ledger_entry_id)
);

-- The link an owner gets on WhatsApp ("hisaab"): single use, 15 minutes. Only the token's hash is stored.
CREATE TABLE owner_login_token (
    id         uuid PRIMARY KEY,
    hotel_id   uuid NOT NULL REFERENCES hotel,
    token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL
);

-- A signed-in owner page (30 days), for one hotel. The ls_owner cookie carries its id, signed.
CREATE TABLE owner_session (
    id         uuid PRIMARY KEY,
    hotel_id   uuid NOT NULL REFERENCES hotel,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL
);

-- Uploaded photos: WebP at 400, 800 and 1600 px wide in object storage (<storage_key>-<width>.webp), EXIF removed.
-- url holds the widest variant's public URL, so everything that lists photos keeps working; seed photos have no key.
ALTER TABLE hotel_photo
    ADD COLUMN storage_key text UNIQUE,
    ADD COLUMN width int,
    ADD COLUMN height int,
    ADD COLUMN widths int[],
    ADD COLUMN created_at timestamptz;
-- Reordering rewrites every sort_order in one transaction, so the uniqueness is checked at commit.
ALTER TABLE hotel_photo DROP CONSTRAINT hotel_photo_hotel_id_sort_order_key;
ALTER TABLE hotel_photo ADD CONSTRAINT hotel_photo_hotel_id_sort_order_key UNIQUE (hotel_id, sort_order)
    DEFERRABLE INITIALLY DEFERRED;
