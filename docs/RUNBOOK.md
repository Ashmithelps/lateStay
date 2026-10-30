# Runbook

What to do when something goes wrong in staging or production: restoring data, rolling back a deploy, rotating a
secret. Environments and variables are in [DEPLOY.md](DEPLOY.md).

## Backups

| What | Where | Kept | Set up |
| --- | --- | --- | --- |
| Daily backups | Supabase (each project) | 7 days (Pro plan) | Supabase > Project > Database > Backups. Needs the Pro plan; the free plan has none you can restore |
| Point-in-time restore | Supabase (production) | 7 days, to the second | Optional paid add-on: Project Settings > Add-ons > Point in time recovery |
| Weekly logical dump | R2 bucket `latestay-backups`, `staging/` and `production/` | 30 days | `.github/workflows/backup.yml`, Sundays 03:00 IST, `scripts/backup.sh` |

The weekly dump is `pg_dump --format=custom`, encrypted with [age](https://age-encryption.org) to a public key. Dumps
contain phone numbers, so the private key is kept **offline** (a password manager entry, not on any server or in
GitHub). Without it no dump can be read.

### Setting up the weekly dump (once)

1. `age-keygen -o latestay-backup.key` on your own machine. Save the file in your password manager; the line
   `# public key: age1…` is the recipient.
2. Cloudflare > R2 > create bucket `latestay-backups`. Bucket > Settings > Object lifecycle rules > add "Delete
   objects 30 days after upload" (the script also deletes older dumps; this is the safety net).
3. R2 > Manage API tokens > a token with Object Read & Write on `latestay-backups` only.
4. GitHub > Settings > Environments: create `staging-backup` and `production-backup` (separate from the deploy
   environments, so backups don't wait for a deploy approval). In each:
   - secrets `BACKUP_DB_URL` (`postgresql://postgres:…@db.<ref>.supabase.co:5432/postgres?sslmode=require`; a
     read-only role is better: see below), `BACKUP_R2_ACCESS_KEY_ID`, `BACKUP_R2_SECRET_ACCESS_KEY`;
   - variables `BACKUP_AGE_RECIPIENT` (the `age1…` public key), `BACKUP_R2_ENDPOINT`
     (`https://<account id>.r2.cloudflarestorage.com`), `BACKUP_R2_BUCKET` (`latestay-backups`).
5. Actions > Weekly database backup > Run workflow, and check both jobs pass.

A read-only role for dumps (run once per database in the Supabase SQL editor):

```sql
CREATE ROLE backup_reader LOGIN PASSWORD '…' IN ROLE pg_read_all_data;
```

## Restoring data

### Which backup?

- Data lost or broken in the last few minutes or hours, production: **point-in-time restore** (if the add-on is on).
- Otherwise: Supabase's **daily backup** from before the problem.
- Supabase backups gone or unusable, or you need the data elsewhere: the **weekly dump**.

Before restoring production, stop writes so nothing new is lost or mixed in: `fly scale count 0 -a latestay-api`
(travellers see an error page; owners' WhatsApp replies queue at Meta and are redelivered for a while).

### Supabase daily backup or point in time

Supabase > Project > Database > Backups > pick the backup (or the time) > Restore. The project is unavailable for
a few minutes and comes back with the old data under the same connection string. Then `fly scale count 1 -a
latestay-api`. Flyway runs on start; a restore to before a migration simply reapplies it.

### From a weekly dump

Restore into a **new** database first, check it, then point the app at it:

```sh
export BACKUP_ENV=production BACKUP_R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com BACKUP_R2_BUCKET=latestay-backups
export AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=… AWS_DEFAULT_REGION=auto
export AGE_IDENTITY_FILE=~/latestay-backup.key
export SCRATCH_DB_URL='postgresql://postgres:…@db.<new project ref>.supabase.co:5432/postgres?sslmode=require'
sh scripts/restore-drill.sh            # the newest dump; BACKUP_FILE=… for a specific one
```

It creates a new database `restore_drill_<time>` on that server, restores into it and prints the checks (Flyway
version, hotels, bookings, ledger total, newest booking). If they look right, set `DB_URL` on the backend to it
(`fly secrets set DB_URL=… -a latestay-api`), which redeploys.

### Restore drill

Run `scripts/restore-drill.sh` against a scratch server at least once a quarter, and after any change to the
backup setup. Record it here.

| Date | Environment | Backup | Result |
| --- | --- | --- | --- |
| 29 Oct 2026 | local (dev database, before staging existed) | `local/latestay-local-2026-10-29T182600Z.dump.age`, made by `scripts/backup.sh` into an S3-compatible test bucket | **Worked.** Restored into a fresh PostGIS 16 database; every check matched the source: Flyway version 8, 14 hotels, 84 bookings, 23 ledger entries with ₹1,000 owed, newest booking 2 Nov 2026 18:08 UTC. The stored file was age-encrypted (no phone number readable in it) |
| — | staging | first weekly dump after staging goes live | **To do**: run `restore-drill.sh` with staging's `latestay-backups/staging/` and record the result |

## Rolling back a bad deploy

Deploys: staging on every push to `main`, production only from a `v*` tag after approval
([DEPLOY.md](DEPLOY.md#github-actions)).

- **Web:** Vercel > the project > Deployments > the previous production deployment > Promote to Production. Instant.
- **Backend / imaging:** `fly releases -a latestay-api` lists releases with their image; roll back with
  `fly deploy -a latestay-api --image <previous image>` (the image is shown by `fly releases --image`). Production
  images are labelled with their tag, so `registry.fly.io/latestay-api:v1.4.0` works too.
- **A migration ran:** migrations only move forward and the previous release must work with the new schema (add
  columns, don't rename or drop in the same release). If it doesn't, write a new migration that undoes the change
  and release it; restore data from a backup only if rows were lost.
- **Then:** tag a fixed release (`v1.4.1`) rather than leaving production on a rolled-back image, so main and
  production agree again.

Scheduled jobs are safe during a deploy's overlap: ShedLock lets only one machine run each job.

## Rotating secrets

Rotate after anyone who had a secret leaves, if one may have leaked, and otherwise once a year. Set the new value
(`fly secrets set NAME=… -a <app>` for Fly, which redeploys; Vercel > Settings > Environment Variables, then
redeploy; GitHub > Settings > Environments for Actions), check the app, then revoke the old value at its source.

| Secret | Where it's set | Get a new one | What happens when it changes |
| --- | --- | --- | --- |
| `DB_PASSWORD` | Fly | Supabase > Database > Reset password | The app reconnects on redeploy. Update `BACKUP_DB_URL` too if it uses the same user |
| `ADMIN_PASSWORD` | Fly | `openssl rand -base64 24` | Admins sign in again |
| `RAZORPAY_KEY_SECRET` (and `RAZORPAY_KEY_ID`) | Fly | Razorpay > API Keys > Regenerate | The old key stops at once: set the new one first |
| `RAZORPAY_WEBHOOK_SECRET` | Fly + Razorpay | Edit the webhook in Razorpay, type a new secret | Set both within minutes; webhooks in between fail and Razorpay retries them |
| `WA_ACCESS_TOKEN` | Fly | Meta Business > System users > Generate token | Messages queue and send once it's set |
| `WA_APP_SECRET` | Fly | Meta app > Settings > Basic > Reset | Webhooks fail until both match; Meta retries |
| `WA_VERIFY_TOKEN` | Fly + Meta | Any new string | Only used when (re)verifying the webhook |
| `PHONE_VERIFICATION_SECRET` | Fly | `openssl rand -base64 48` | Every "verified on this device" cookie is void: travellers verify again |
| `OWNER_SESSION_SECRET` | Fly | `openssl rand -base64 48` | Every owner is signed out and sends "hisaab" again |
| `IMAGING_TOKEN` | Fly (api and imaging apps) | `openssl rand -hex 32` | Set on imaging first, then api; uploads fail in between |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | Fly | R2 > Manage API tokens | Uploads fail until set; photos stay online |
| `LLM_API_KEY` | Fly | Provider console (set the monthly limit on the new key too) | Replies use templates until it's set |
| `POSTHOG_API_KEY` | Fly | PostHog > Project settings | Events wait in the database and are sent once it's set |
| `SENTRY_DSN` | Fly + Vercel | Sentry > Client Keys | Errors go to the new key |
| `FLY_API_TOKEN` | GitHub environments | `fly tokens create deploy -a <app>` | Deploys fail until it's set |
| `VERCEL_TOKEN` | GitHub environments | Vercel > Account > Tokens | Same |
| `BACKUP_R2_*` | GitHub `*-backup` environments | R2 > Manage API tokens | The next weekly dump fails until it's set |
| age backup key | Your password manager; public key in GitHub | `age-keygen` | Keep the **old** private key as long as dumps made with it exist (30 days) |

Staging and production have separate values for every secret above; never copy one environment's secret into the
other.
