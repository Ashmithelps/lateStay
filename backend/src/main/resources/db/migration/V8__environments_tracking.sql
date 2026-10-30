-- ShedLock, launch tracking (UTM, analytics events, LLM spend per turn), promo codes.

-- One run of each scheduled job at a time across instances (ShedLock's JDBC provider).
CREATE TABLE shedlock (
    name       varchar(64) PRIMARY KEY,
    lock_until timestamptz NOT NULL,
    locked_at  timestamptz NOT NULL,
    locked_by  varchar(255) NOT NULL
);

-- First-touch UTM tags (kept by the browser from the first visit), the browser's random analytics id, and the
-- town the search was nearest to.
ALTER TABLE stay_request
    ADD COLUMN utm_source   text CHECK (length(utm_source) <= 100),
    ADD COLUMN utm_medium   text CHECK (length(utm_medium) <= 100),
    ADD COLUMN utm_campaign text CHECK (length(utm_campaign) <= 100),
    ADD COLUMN analytics_id text CHECK (length(analytics_id) <= 64),
    ADD COLUMN town         text;
ALTER TABLE booking
    ADD COLUMN utm_source   text,
    ADD COLUMN utm_medium   text,
    ADD COLUMN utm_campaign text,
    -- Taken off the deposit by a promo code; LateStay pays it to the hotel (ledger PROMO_SUBSIDY).
    ADD COLUMN promo_discount_paise bigint NOT NULL DEFAULT 0 CHECK (promo_discount_paise >= 0);

-- deposit_amount stays "what the traveller paid online"; with a promo code that's the deposit minus the discount,
-- so the desk amount and the commission are checked against the deposit before the discount.
ALTER TABLE booking DROP CONSTRAINT booking_check2;
ALTER TABLE booking ADD CONSTRAINT booking_desk_amount_check
    CHECK (pay_at_desk * 100 = agreed_price * 100 - deposit_amount * 100 - promo_discount_paise);
ALTER TABLE booking DROP CONSTRAINT booking_check1;
ALTER TABLE booking ADD CONSTRAINT booking_commission_check
    CHECK (commission_amount >= 0 AND commission_amount * 100 <= deposit_amount * 100 + promo_discount_paise);

-- What each bot turn cost (null for turns answered without the LLM).
ALTER TABLE negotiation_turn
    ADD COLUMN llm_calls          int,
    ADD COLUMN input_tokens       int,
    ADD COLUMN output_tokens      int,
    ADD COLUMN model              text,
    ADD COLUMN llm_cost_micro_usd bigint;
CREATE INDEX negotiation_turn_created_idx ON negotiation_turn (created_at) WHERE llm_cost_micro_usd IS NOT NULL;

-- Promo codes: a flat discount off the deposit, whole rupees, between two dates, a total number of uses, and once
-- per verified phone.
CREATE TABLE promo_code (
    id             uuid PRIMARY KEY,
    code           text NOT NULL UNIQUE CHECK (code = upper(code) AND code ~ '^[A-Z0-9]{3,20}$'),
    discount_paise bigint NOT NULL CHECK (discount_paise > 0 AND discount_paise % 100 = 0),
    valid_from     timestamptz NOT NULL,
    valid_until    timestamptz NOT NULL,
    max_uses       int NOT NULL CHECK (max_uses > 0),
    active         boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL,
    CHECK (valid_until > valid_from)
);

-- RESERVED when the booking is created, USED once it's paid, RELEASED if it expires unpaid or is refunded.
CREATE TABLE promo_redemption (
    id             uuid PRIMARY KEY,
    promo_id       uuid NOT NULL REFERENCES promo_code,
    booking_id     uuid NOT NULL UNIQUE REFERENCES booking,
    phone_hash     text NOT NULL,
    discount_paise bigint NOT NULL,
    status         text NOT NULL CHECK (status IN ('RESERVED', 'USED', 'RELEASED')),
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);
CREATE UNIQUE INDEX promo_redemption_once_per_phone ON promo_redemption (promo_id, phone_hash)
    WHERE status <> 'RELEASED';
CREATE INDEX promo_redemption_promo_idx ON promo_redemption (promo_id) WHERE status <> 'RELEASED';

ALTER TABLE ledger_entry DROP CONSTRAINT ledger_entry_type_check;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_type_check
    CHECK (type IN ('DEPOSIT_COLLECTED', 'COMMISSION', 'REFUND', 'PAYOUT', 'ADJUSTMENT', 'PROMO_SUBSIDY'));
ALTER TABLE ledger_entry DROP CONSTRAINT ledger_entry_check1;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_booking_check
    CHECK (type IN ('PAYOUT', 'ADJUSTMENT') OR booking_id IS NOT NULL);
DROP INDEX ledger_entry_once_idx;
CREATE UNIQUE INDEX ledger_entry_once_idx ON ledger_entry (booking_id, type)
    WHERE type IN ('DEPOSIT_COLLECTED', 'COMMISSION', 'REFUND', 'PROMO_SUBSIDY');

-- Funnel events, from the web app (POST /api/events) and the backend. No names, phone numbers or codes: only
-- whitelisted properties (analytics.AnalyticsEvents). Forwarded to PostHog when POSTHOG_API_KEY is set.
CREATE TABLE analytics_event (
    id              bigserial PRIMARY KEY,
    name            text NOT NULL,
    distinct_id     text NOT NULL CHECK (length(distinct_id) <= 64),
    stay_request_id uuid,
    town            text,
    utm_source      text,
    properties      jsonb NOT NULL DEFAULT '{}',
    occurred_at     timestamptz NOT NULL,
    forwarded_at    timestamptz
);
CREATE INDEX analytics_event_funnel_idx ON analytics_event (name, occurred_at);
CREATE INDEX analytics_event_unforwarded_idx ON analytics_event (id) WHERE forwarded_at IS NULL;
