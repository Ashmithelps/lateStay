#!/usr/bin/env sh
# Restore drill: the newest backup of an environment, restored into a scratch database, then checked.
# Never point SCRATCH_DB_URL at a real environment's server.
#
# Needs: pg_restore, psql, age, aws (CLI v2).
# Env:   BACKUP_ENV, BACKUP_R2_ENDPOINT, BACKUP_R2_BUCKET, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
#        AGE_IDENTITY_FILE  the age private key (kept offline; RUNBOOK.md)
#        SCRATCH_DB_URL     postgresql://... of a scratch server's maintenance database (e.g. /postgres) with
#                           PostGIS installed; the drill creates a new, empty database there for each run
#        BACKUP_FILE        optional: a specific dump name instead of the newest
set -eu

: "${BACKUP_ENV:?}" "${BACKUP_R2_ENDPOINT:?}" "${BACKUP_R2_BUCKET:?}" "${AGE_IDENTITY_FILE:?}" "${SCRATCH_DB_URL:?}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# R2 (and S3-compatible test servers) don't need the CLI's default extra checksums on every request.
export AWS_REQUEST_CHECKSUM_CALCULATION="${AWS_REQUEST_CHECKSUM_CALCULATION:-when_required}"
export AWS_RESPONSE_CHECKSUM_VALIDATION="${AWS_RESPONSE_CHECKSUM_VALIDATION:-when_required}"
s3() { aws --endpoint-url "$BACKUP_R2_ENDPOINT" "$@"; }

NAME="${BACKUP_FILE:-$(s3 s3 ls "s3://${BACKUP_R2_BUCKET}/${BACKUP_ENV}/" | awk '{print $4}' | sort | tail -1)}"
[ -n "$NAME" ] || { echo "No backups found for ${BACKUP_ENV}" >&2; exit 1; }
echo "Restoring ${BACKUP_ENV}/${NAME}"
s3 s3 cp "s3://${BACKUP_R2_BUCKET}/${BACKUP_ENV}/${NAME}" "$TMP/backup.dump.age" --only-show-errors
age -d -i "$AGE_IDENTITY_FILE" "$TMP/backup.dump.age" > "$TMP/backup.dump"

# A new database from template0 (no extensions yet): the dump creates PostGIS itself, as Flyway's V1 did.
DRILL_DB="restore_drill_$(date -u +%Y%m%d%H%M%S)"
psql "$SCRATCH_DB_URL" -v ON_ERROR_STOP=1 -q -c "CREATE DATABASE ${DRILL_DB} TEMPLATE template0"
TARGET="$(echo "$SCRATCH_DB_URL" | sed -E "s#/[^/?]+(\?|$)#/${DRILL_DB}\1#")"
pg_restore --no-owner --no-privileges --exit-on-error --dbname "$TARGET" "$TMP/backup.dump"

echo "Checks (database ${DRILL_DB}):"
psql "$TARGET" -v ON_ERROR_STOP=1 -At -c "
  SELECT 'flyway version: ' || max(version::int) FROM flyway_schema_history WHERE success;
  SELECT 'hotels: ' || count(*) FROM hotel;
  SELECT 'bookings: ' || count(*) FROM booking;
  SELECT 'ledger entries: ' || count(*) || ', balance owed (paise): ' || COALESCE(sum(amount_paise), 0) FROM ledger_entry;
  SELECT 'newest booking: ' || COALESCE(max(created_at)::text, 'none') FROM booking;
  SELECT 'postgis: ' || postgis_version();"
echo "Restore drill OK: ${BACKUP_ENV}/${NAME} -> ${DRILL_DB} (drop it with: DROP DATABASE ${DRILL_DB};)"
