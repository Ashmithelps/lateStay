-- Safety features: room holds, phone verification, arrival checks, disputes and hotel failures.

-- New booking states.
ALTER TABLE booking DROP CONSTRAINT booking_status_check;
ALTER TABLE booking ADD CONSTRAINT booking_status_check CHECK (status IN ('PENDING_PAYMENT', 'DEPOSIT_PAID',
    'HOTEL_CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'EXPIRED', 'HOTEL_DECLINED', 'HOTEL_TIMEOUT', 'REFUNDED',
    'CANCELLED', 'NO_SHOW', 'DISPUTED', 'HOTEL_FAILED'));

ALTER TABLE booking
    -- When the owner was asked "did they arrive?" (arrive_by + 60 min).
    ADD COLUMN arrival_check_sent_at timestamptz,
    -- A confirmed booking nobody answered about becomes COMPLETED here: 10:00 the morning after the night, and
    -- at least 2 hours after the arrival check.
    ADD COLUMN complete_by timestamptz,
    -- TRAVELLER_ARRIVED ("I did arrive" after the owner said no) or TURNED_AWAY ("the hotel turned me away").
    ADD COLUMN dispute_reason text CHECK (dispute_reason IN ('TRAVELLER_ARRIVED', 'TURNED_AWAY')),
    ADD COLUMN disputed_at timestamptz,
    ADD COLUMN settled_at timestamptz,
    ADD COLUMN hold_id uuid,
    -- After an admin Rescue: a fresh search (same place and guests, arriving now) for the traveller's alternatives.
    ADD COLUMN rescue_stay_request_id uuid REFERENCES stay_request;

-- How many bookings this hotel has failed (admin Rescue).
ALTER TABLE hotel ADD COLUMN reliability_failures int NOT NULL DEFAULT 0 CHECK (reliability_failures >= 0);

-- A room set aside for a negotiation from the moment the traveller accepts, then for its booking.
CREATE TABLE room_hold (
    id           uuid PRIMARY KEY,
    hotel_id     uuid NOT NULL REFERENCES hotel,
    room_type_id uuid NOT NULL REFERENCES room_type,
    night_of     date NOT NULL,
    session_id   uuid NOT NULL REFERENCES negotiation_session,
    booking_id   uuid REFERENCES booking,
    expires_at   timestamptz NOT NULL,
    -- ACTIVE: holding a room until expires_at. CONVERTED: a booking took it over (the booking now counts).
    -- RELEASED: given back (payment expired, cancelled, declined, timed out, turned away). EXPIRED: not booked in time.
    status       text NOT NULL CHECK (status IN ('ACTIVE', 'CONVERTED', 'RELEASED', 'EXPIRED')),
    created_at   timestamptz NOT NULL,
    released_at  timestamptz
);
CREATE UNIQUE INDEX room_hold_session_active_idx ON room_hold (session_id) WHERE status = 'ACTIVE';
CREATE INDEX room_hold_night_idx ON room_hold (hotel_id, night_of) WHERE status = 'ACTIVE';
ALTER TABLE booking ADD CONSTRAINT booking_hold_fk FOREIGN KEY (hold_id) REFERENCES room_hold;
CREATE INDEX booking_night_live_idx ON booking (hotel_id, night_of)
    WHERE status IN ('PENDING_PAYMENT', 'DEPOSIT_PAID', 'HOTEL_CONFIRMED', 'CHECKED_IN', 'DISPUTED');

/*
 * Rooms a hotel can still sell for a night: the owner's latest report, minus active holds, minus live bookings
 * made after that report (the owner's number already left out the ones before it). Zero while a guest's
 * "the hotel turned me away" dispute is open for that night. Zero when the owner hasn't answered for the night.
 */
CREATE FUNCTION rooms_available(p_hotel uuid, p_night date, p_now timestamptz) RETURNS int
LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM booking d
                     WHERE d.hotel_id = p_hotel AND d.night_of = p_night
                       AND d.status = 'DISPUTED' AND d.dispute_reason = 'TURNED_AWAY') THEN 0
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

-- Phone verification by WhatsApp one-time code. Only HMAC hashes (server secret): no phone number, code or IP.
CREATE TABLE phone_verification (
    id          uuid PRIMARY KEY,
    phone_hash  text NOT NULL,
    code_hash   text NOT NULL,
    ip_hash     text NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    int NOT NULL DEFAULT 0,
    -- PENDING -> VERIFIED, or LOCKED after 3 wrong codes. Expiry is read from expires_at.
    status      text NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'LOCKED')),
    created_at  timestamptz NOT NULL,
    verified_at timestamptz
);
CREATE INDEX phone_verification_phone_idx ON phone_verification (phone_hash, created_at);
