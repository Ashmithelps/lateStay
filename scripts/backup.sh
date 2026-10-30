#!/usr/bin/env sh
# Weekly logical backup: pg_dump (custom format) -> encrypted with age -> S3-compatible storage (R2).
# Dumps contain phone numbers, so they are encrypted to a public key; the private key stays offline (RUNBOOK.md).
# Dumps older than BACKUP_RETENTION_DAYS (30) are deleted (the bucket's lifecycle rule does the same).
#
# Needs: pg_dump (same major version as the server or newer), age, aws (CLI v2).
# Env:   BACKUP_DB_URL        postgresql://user:pass@host:5432/db?sslmode=require
#        BACKUP_AGE_RECIPIENT age1... public key
#        BACKUP_ENV           staging | production (the folder in the bucket)
#        BACKUP_R2_ENDPOINT   https://<account>.r2.cloudflarestorage.com
#        BACKUP_R2_BUCKET     latestay-backups
#        AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (an R2 token for that bucket); AWS_DEFAULT_REGION=auto
set -eu

: "${BACKUP_DB_URL:?}" "${BACKUP_AGE_RECIPIENT:?}" "${BACKUP_ENV:?}" "${BACKUP_R2_ENDPOINT:?}" "${BACKUP_R2_BUCKET:?}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
STAMP="$(date -u +%Y-%m-%dT%H%M%SZ)"
FILE="latestay-${BACKUP_ENV}-${STAMP}.dump.age"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# R2 (and S3-compatible test servers) don't need the CLI's default extra checksums on every request.
export AWS_REQUEST_CHECKSUM_CALCULATION="${AWS_REQUEST_CHECKSUM_CALCULATION:-when_required}"
export AWS_RESPONSE_CHECKSUM_VALIDATION="${AWS_RESPONSE_CHECKSUM_VALIDATION:-when_required}"
s3() { aws --endpoint-url "$BACKUP_R2_ENDPOINT" "$@"; }

echo "Dumping ${BACKUP_ENV}..."
pg_dump --format=custom --no-owner --no-privileges "$BACKUP_DB_URL" | age -r "$BACKUP_AGE_RECIPIENT" > "$TMP/$FILE"
SIZE="$(wc -c < "$TMP/$FILE" | tr -d ' ')"
[ "$SIZE" -gt 1000 ] || { echo "Backup is suspiciously small ($SIZE bytes)" >&2; exit 1; }

s3 s3 cp "$TMP/$FILE" "s3://${BACKUP_R2_BUCKET}/${BACKUP_ENV}/${FILE}" --only-show-errors
echo "Uploaded ${BACKUP_ENV}/${FILE} (${SIZE} bytes)"

# Retention: names carry the date, so delete the ones older than the cutoff.
CUTOFF="$(date -u -d "-${RETENTION_DAYS} days" +%Y-%m-%d 2>/dev/null || date -u -v-"${RETENTION_DAYS}"d +%Y-%m-%d)"
s3 s3 ls "s3://${BACKUP_R2_BUCKET}/${BACKUP_ENV}/" | awk '{print $4}' | while read -r name; do
  day="$(echo "$name" | sed -n 's/^latestay-[a-z]*-\([0-9-]\{10\}\)T.*/\1/p')"
  if [ -n "$day" ] && [ "$day" \< "$CUTOFF" ]; then
    s3 s3 rm "s3://${BACKUP_R2_BUCKET}/${BACKUP_ENV}/${name}" --only-show-errors
    echo "Deleted ${name} (older than ${RETENTION_DAYS} days)"
  fi
done
