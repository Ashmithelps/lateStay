-- Reviews and reliability, occupancy / weekend / season pricing, two-room groups, share links.

-- Ratings now come only from reviews (com.latestay.review); the seeded ones are gone.
ALTER TABLE hotel DROP COLUMN rating;

ALTER TABLE hotel
    -- Rooms the hotel has in all, for occupancy pricing (rooms free tonight out of these).
    ADD COLUMN total_rooms int NOT NULL DEFAULT 10 CHECK (total_rooms BETWEEN 1 AND 500),
    -- Hidden from search because its reliability fell below the threshold, until an admin reviews it.
    ADD COLUMN reliability_paused_at timestamptz,
    -- The admin's "Reviewed, show again": only incidents after this can pause the hotel again.
    ADD COLUMN reliability_reviewed_at timestamptz;

-- One review per booking, after a stay that ended CHECKED_IN or COMPLETED. Stars always count; an admin can hide
-- abusive text.
CREATE TABLE review (
    id             uuid PRIMARY KEY,
    booking_id     uuid NOT NULL UNIQUE REFERENCES booking,
    hotel_id       uuid NOT NULL REFERENCES hotel,
    stars          int NOT NULL CHECK (stars BETWEEN 1 AND 5),
    tags           text[] NOT NULL DEFAULT '{}'
                       CHECK (tags <@ ARRAY['CLEAN', 'WARM', 'QUIET', 'FRIENDLY_DESK', 'AS_SHOWN']),
    body           text CHECK (length(body) BETWEEN 1 AND 300),
    text_hidden_at timestamptz,
    created_at     timestamptz NOT NULL
);
CREATE INDEX review_hotel_idx ON review (hotel_id, created_at DESC);

-- Busy periods per town: the opening offer is multiplied by multiplier_percent (with the weekend factor, clamped to
-- 70-150%). Floors never move. Ranges in one town may not overlap.
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE season_period (
    id                 uuid PRIMARY KEY,
    town               text NOT NULL,
    starts_on          date NOT NULL,
    ends_on            date NOT NULL,
    multiplier_percent int NOT NULL CHECK (multiplier_percent BETWEEN 50 AND 200),
    label              text NOT NULL CHECK (length(label) BETWEEN 1 AND 60),
    created_at         timestamptz NOT NULL,
    CHECK (starts_on <= ends_on),
    EXCLUDE USING gist (lower(town) WITH =, daterange(starts_on, ends_on, '[]') WITH &&)
);

-- The pricing inputs a session opened with (PricingContext), so it keeps its prices. Version 1 = before occupancy
-- pricing: existing sessions behave exactly as before.
ALTER TABLE negotiation_session
    ADD COLUMN pricing_version int NOT NULL DEFAULT 1 CHECK (pricing_version IN (1, 2)),
    ADD COLUMN rooms_free      int NOT NULL DEFAULT 1 CHECK (rooms_free >= 0),
    ADD COLUMN total_rooms     int NOT NULL DEFAULT 1 CHECK (total_rooms >= 1),
    ADD COLUMN demand_percent  int NOT NULL DEFAULT 100 CHECK (demand_percent BETWEEN 70 AND 150),
    ADD COLUMN rooms           int NOT NULL DEFAULT 1 CHECK (rooms BETWEEN 1 AND 2);

-- The rooms a negotiation covers (line 1 is negotiation_session.room_type_id). Two rooms are one negotiation: list
-- and floor are the sums of both rooms'.
CREATE TABLE negotiation_room (
    session_id   uuid NOT NULL REFERENCES negotiation_session ON DELETE CASCADE,
    line_no      int NOT NULL CHECK (line_no BETWEEN 1 AND 2),
    room_type_id uuid NOT NULL REFERENCES room_type,
    list_price   int NOT NULL CHECK (list_price > 0),
    PRIMARY KEY (session_id, line_no)
);
INSERT INTO negotiation_room (session_id, line_no, room_type_id, list_price)
SELECT id, 1, room_type_id, list_price FROM negotiation_session;

-- A two-room group holds one row per room, taken together or not at all.
ALTER TABLE room_hold ADD COLUMN line_no int NOT NULL DEFAULT 1 CHECK (line_no BETWEEN 1 AND 2);
DROP INDEX room_hold_session_active_idx;
CREATE UNIQUE INDEX room_hold_session_active_idx ON room_hold (session_id, line_no) WHERE status = 'ACTIVE';

ALTER TABLE booking ADD COLUMN rooms int NOT NULL DEFAULT 1 CHECK (rooms BETWEEN 1 AND 2);
CREATE TABLE booking_room (
    booking_id   uuid NOT NULL REFERENCES booking ON DELETE CASCADE,
    line_no      int NOT NULL CHECK (line_no BETWEEN 1 AND 2),
    room_type_id uuid NOT NULL REFERENCES room_type,
    PRIMARY KEY (booking_id, line_no)
);
INSERT INTO booking_room (booking_id, line_no, room_type_id) SELECT id, 1, room_type_id FROM booking;

/*
 * As in V7, except that a live booking takes as many rooms as it has (two for a group).
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
                - (SELECT COALESCE(sum(b.rooms), 0) FROM booking b
                   WHERE b.hotel_id = p_hotel AND b.night_of = p_night AND b.created_at > a.reported_at
                     AND b.status IN ('PENDING_PAYMENT', 'DEPOSIT_PAID', 'HOTEL_CONFIRMED', 'CHECKED_IN')))
            FROM nightly_availability a WHERE a.hotel_id = p_hotel AND a.night_of = p_night), 0)
    END
$$;

-- "Share with family": a read-only page (hotel, address, map, arrival, status; never the code, phone numbers or
-- prices) until 24 hours after the leave time, or until the traveller turns it off. The token is stored as is:
-- the page it opens shows nothing that needs protecting more than that.
CREATE TABLE booking_share (
    token      text PRIMARY KEY CHECK (token ~ '^[A-Za-z0-9_-]{22,64}$'),
    booking_id uuid NOT NULL REFERENCES booking,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX booking_share_live_idx ON booking_share (booking_id) WHERE revoked_at IS NULL;

-- Reliability and town prices read bookings by hotel and payment time.
CREATE INDEX booking_hotel_paid_idx ON booking (hotel_id, paid_at) WHERE paid_at IS NOT NULL;
