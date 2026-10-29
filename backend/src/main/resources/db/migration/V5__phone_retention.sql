-- Traveller phone numbers are deleted 90 days after the stay (see /privacy). The booking keeps everything else.
ALTER TABLE booking ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE booking ADD COLUMN phone_deleted_at timestamptz;
ALTER TABLE booking ADD CONSTRAINT booking_phone_deleted CHECK (phone IS NOT NULL OR phone_deleted_at IS NOT NULL);
CREATE INDEX booking_phone_retention_idx ON booking (leave_at) WHERE phone IS NOT NULL;
