-- Bookings and Razorpay payments. Status changes are guarded in code (BookingTransitions); every change is logged
-- in booking_event.

CREATE TABLE booking (
    id                uuid PRIMARY KEY,
    session_id        uuid NOT NULL REFERENCES negotiation_session,
    hotel_id          uuid NOT NULL REFERENCES hotel,
    room_type_id      uuid NOT NULL REFERENCES room_type,
    night_of          date NOT NULL,
    guest_name        text NOT NULL CHECK (length(guest_name) BETWEEN 1 AND 80),
    -- E.164 Indian mobile number: +91 then 10 digits starting 6-9.
    phone             text NOT NULL CHECK (phone ~ '^\+91[6-9][0-9]{9}$'),
    guests            int NOT NULL CHECK (guests > 0),
    arrive_at         timestamptz NOT NULL,
    -- The hotel holds the room until here; NO_SHOW an hour after.
    arrive_by         timestamptz NOT NULL,
    leave_at          timestamptz NOT NULL,
    agreed_price      int NOT NULL CHECK (agreed_price > 0),
    deposit_amount    int NOT NULL CHECK (deposit_amount > 0 AND deposit_amount <= agreed_price),
    pay_at_desk       int NOT NULL CHECK (pay_at_desk >= 0),
    commission_amount int NOT NULL CHECK (commission_amount >= 0 AND commission_amount <= deposit_amount),
    code              char(4) NOT NULL CHECK (code ~ '^[0-9]{4}$'),
    status            text NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'DEPOSIT_PAID', 'HOTEL_CONFIRMED',
                          'CHECKED_IN', 'COMPLETED', 'EXPIRED', 'HOTEL_DECLINED', 'HOTEL_TIMEOUT', 'REFUNDED',
                          'CANCELLED', 'NO_SHOW')),
    pay_by            timestamptz NOT NULL,
    confirm_by        timestamptz,
    paid_at           timestamptz,
    confirmed_at      timestamptz,
    checked_in_at     timestamptz,
    closed_at         timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CHECK (pay_at_desk = agreed_price - deposit_amount),
    CHECK (arrive_at <= arrive_by AND arrive_by <= leave_at)
);

-- One live booking per negotiation; an expired or cancelled one can be booked again.
CREATE UNIQUE INDEX booking_session_live_idx ON booking (session_id)
    WHERE status NOT IN ('EXPIRED', 'CANCELLED');
-- Desk codes are unique among a hotel's live bookings for a night.
CREATE UNIQUE INDEX booking_code_idx ON booking (hotel_id, night_of, code)
    WHERE status IN ('PENDING_PAYMENT', 'DEPOSIT_PAID', 'HOTEL_CONFIRMED', 'CHECKED_IN');
CREATE INDEX booking_status_idx ON booking (status);

CREATE TABLE booking_event (
    id          bigserial PRIMARY KEY,
    booking_id  uuid NOT NULL REFERENCES booking ON DELETE CASCADE,
    from_status text,
    to_status   text NOT NULL,
    -- TRAVELLER, ADMIN, RAZORPAY or JOB
    actor       text NOT NULL,
    created_at  timestamptz NOT NULL
);
CREATE INDEX booking_event_booking_idx ON booking_event (booking_id);

-- Amounts in rupees; the gateway converts to paise.
CREATE TABLE payment (
    id                  bigserial PRIMARY KEY,
    booking_id          uuid NOT NULL REFERENCES booking ON DELETE CASCADE,
    razorpay_order_id   text NOT NULL UNIQUE,
    razorpay_payment_id text UNIQUE,
    amount              int NOT NULL CHECK (amount > 0),
    -- REFUND_PENDING: the refund job still has to call Razorpay. REFUND_REQUESTED: called, waiting for
    -- refund.processed.
    status              text NOT NULL CHECK (status IN ('CREATED', 'CAPTURED', 'REFUND_PENDING', 'REFUND_REQUESTED',
                            'REFUNDED')),
    refund_id           text UNIQUE,
    refund_attempts     int NOT NULL DEFAULT 0,
    last_error          text,
    -- The latest Razorpay webhook payload about this payment.
    raw_event           jsonb,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);
CREATE INDEX payment_booking_idx ON payment (booking_id);
CREATE INDEX payment_refund_pending_idx ON payment (status) WHERE status = 'REFUND_PENDING';

-- Webhook deliveries already handled (Razorpay retries; x-razorpay-event-id is stable across retries).
CREATE TABLE razorpay_webhook_event (
    event_id    text PRIMARY KEY,
    event       text NOT NULL,
    received_at timestamptz NOT NULL
);
