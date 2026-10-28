-- Hotels, rooms, pricing rules, availability, stay requests and persisted negotiations.

CREATE TABLE hotel (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text NOT NULL,
    slug            text NOT NULL UNIQUE,
    town            text NOT NULL,
    address         text NOT NULL,
    location        geography(Point, 4326) NOT NULL,
    whatsapp_number text NOT NULL,
    amenities       text[] NOT NULL DEFAULT '{}',
    -- {"couple_friendly": bool, "local_id_ok": bool, "check_in_notes": text}
    policies        jsonb NOT NULL DEFAULT '{}',
    rating          numeric(2, 1) CHECK (rating BETWEEN 0 AND 5),
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX hotel_location_gix ON hotel USING gist (location);
CREATE INDEX hotel_amenities_gin ON hotel USING gin (amenities);

CREATE TABLE hotel_photo (
    id         bigserial PRIMARY KEY,
    hotel_id   uuid NOT NULL REFERENCES hotel ON DELETE CASCADE,
    url        text NOT NULL,
    sort_order int NOT NULL,
    UNIQUE (hotel_id, sort_order)
);

CREATE TABLE room_type (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hotel_id   uuid NOT NULL REFERENCES hotel ON DELETE CASCADE,
    name       text NOT NULL,
    list_price int NOT NULL CHECK (list_price > 0),
    max_guests int NOT NULL CHECK (max_guests > 0)
);
CREATE INDEX room_type_hotel_idx ON room_type (hotel_id);

CREATE TABLE floor_rule (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_type_id uuid NOT NULL REFERENCES room_type ON DELETE CASCADE,
    slot_type    text NOT NULL CHECK (slot_type IN ('LATE_NIGHT', 'EARLY_MORNING')),
    -- Days of the week of night_of: bit 0 = Monday ... bit 6 = Sunday. 127 = every day.
    weekdays     smallint NOT NULL DEFAULT 127 CHECK (weekdays BETWEEN 1 AND 127),
    -- Inclusive range of night_of dates; both NULL = all year.
    season_start date,
    season_end   date,
    floor_price  int NOT NULL CHECK (floor_price > 0),
    -- Shortest stay (arrive to leave, in hours) the rule applies to.
    min_hours    int NOT NULL DEFAULT 0 CHECK (min_hours >= 0),
    CHECK ((season_start IS NULL) = (season_end IS NULL)),
    CHECK (season_start IS NULL OR season_start <= season_end)
);
CREATE INDEX floor_rule_room_type_idx ON floor_rule (room_type_id);

-- One row per hotel and night; the latest report replaces the previous one.
-- night_of: an arrival before 06:00 belongs to the previous date's night.
CREATE TABLE nightly_availability (
    hotel_id    uuid NOT NULL REFERENCES hotel ON DELETE CASCADE,
    night_of    date NOT NULL,
    rooms_free  int NOT NULL CHECK (rooms_free >= 0),
    reported_at timestamptz NOT NULL DEFAULT now(),
    source      text NOT NULL CHECK (source IN ('WHATSAPP', 'ADMIN')),
    PRIMARY KEY (hotel_id, night_of)
);

CREATE TABLE stay_request (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    location   geography(Point, 4326) NOT NULL,
    arrive_at  timestamptz NOT NULL,
    leave_at   timestamptz NOT NULL,
    guests     int NOT NULL CHECK (guests > 0),
    filters    jsonb NOT NULL DEFAULT '[]',
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (leave_at > arrive_at)
);

CREATE TABLE negotiation_session (
    id              uuid PRIMARY KEY,
    stay_request_id uuid NOT NULL REFERENCES stay_request,
    room_type_id    uuid NOT NULL REFERENCES room_type,
    status          text NOT NULL CHECK (status IN ('OPEN', 'AGREED', 'FINAL_OFFER', 'NO_DEAL', 'EXPIRED')),
    current_offer   int NOT NULL,
    agreed_price    int,
    round           int NOT NULL DEFAULT 0,
    -- Pricing policy snapshot: a session keeps the prices it opened with, even if the hotel edits its rules.
    -- floor_price never leaves the backend.
    list_price      int NOT NULL,
    floor_price     int NOT NULL,
    slot_type       text NOT NULL CHECK (slot_type IN ('LATE_NIGHT', 'EARLY_MORNING')),
    max_rounds      int NOT NULL,
    round_to        int NOT NULL,
    arrival_time    time NOT NULL,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (current_offer >= floor_price),
    CHECK (agreed_price IS NULL OR agreed_price >= floor_price),
    CHECK ((status = 'AGREED') = (agreed_price IS NOT NULL))
);
CREATE INDEX negotiation_session_room_type_idx ON negotiation_session (room_type_id);

CREATE TABLE negotiation_turn (
    id               bigserial PRIMARY KEY,
    session_id       uuid NOT NULL REFERENCES negotiation_session ON DELETE CASCADE,
    seq              int NOT NULL,
    role             text NOT NULL CHECK (role IN ('TRAVELLER', 'BOT')),
    text             text NOT NULL,
    intent           jsonb,
    decision         jsonb,
    validator_result text,
    latency_ms       int,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq)
);
