# Deploying LateStay

Four services, plus photo storage, all in or next to Singapore, the closest region these providers offer to India (none of Fly.io, Railway or Render has an Indian region; Vercel and Supabase do have Mumbai, but they should sit next to the backend):

| Service | Provider | Region | What runs there |
| --- | --- | --- | --- |
| Backend | Fly.io | `sin` (Singapore) | `backend/Dockerfile`: Spring Boot, Flyway migrations on start, the booking and WhatsApp jobs |
| Database | Supabase (Postgres 16 + PostGIS) | `ap-southeast-1` (Singapore) | Everything in `backend/src/main/resources/db/migration` |
| Web | Vercel | `sin1` (Singapore) functions, global CDN | `web/` (Next.js) |
| Imaging | Fly.io, private only | `sin` | `imaging/`: Node + sharp, turns admin photo uploads into EXIF-free WebP. No storage, no public port |
| Photos | Cloudflare R2 | automatic (APAC), served by Cloudflare's CDN | The WebP files of uploaded hotel photos |

## Two environments

Everything below exists twice: **staging**, which deploys on every push to `main` and takes only test payments,
and **production**, which deploys only from a version tag after you approve it. They share nothing: separate
databases, WhatsApp numbers, Razorpay keys, LLM keys, buckets and Sentry environments.

| | Staging | Production |
| --- | --- | --- |
| Web (Vercel project) | `latestay-staging` at `staging.latestay.in` | `latestay` at `latestay.in` |
| Backend (Fly app) | `latestay-api-staging` (`backend/fly.staging.toml`) at `api-staging.latestay.in` | `latestay-api` (`backend/fly.toml`) at `api.latestay.in` |
| Imaging (Fly app, private) | `latestay-imaging-staging` (`imaging/fly.staging.toml`) | `latestay-imaging` (`imaging/fly.toml`) |
| Database (Supabase, Singapore) | its own project | its own project, daily backups (Pro), optional point-in-time restore |
| Photos (R2 bucket) | `latestay-photos-staging` | `latestay-photos` |
| WhatsApp | Meta's test number (or a second business number) | your business number |
| Razorpay | **test** keys; live keys are refused | **live** keys (you add them after KYC) with `RAZORPAY_ALLOW_LIVE=true` |
| LLM | own key with its own monthly limit at the provider, plus `LLM_MONTHLY_BUDGET_USD` | same, separately |
| Sentry environment | `staging` | `production` |
| `SPRING_PROFILES_ACTIVE` / `APP_ENV` | `prod,staging` / `staging` (set in fly.staging.toml) | `prod` / `production` (set in fly.toml) |
| Seed hotels | yes, every start | never (the backend refuses to start with seeding on) |
| `NEXT_PUBLIC_APP_ENV` | `staging`: a thin "Staging · test payments" bar on every page; robots.txt disallows everything | `production`: indexable, with sitemap.xml |
| Deploys | every push to `main`, after CI passes | a `v*` tag, after CI passes and you approve |

Every variable in the tables below is set once per environment, with that environment's own value.

### GitHub Actions

- `ci.yml`: tests, lint, build on every branch push and pull request.
- `deploy-staging.yml`: when CI passes on `main`, deploys backend, imaging and web to staging, then checks
  `api-staging.latestay.in/api/health`.
- `deploy-production.yml`: on a tag `v*` (`git tag v1.0.0 && git push origin v1.0.0`) it runs CI on that commit,
  then waits in the **production** environment for approval, then deploys backend and imaging (images labelled with
  the tag) and web, and checks `api.latestay.in/api/health`.
- `backup.yml`: the weekly encrypted pg_dump of both databases ([RUNBOOK.md](RUNBOOK.md#backups)).

One-time setup in GitHub > Settings > Environments:

| Environment | Protection | Secrets | Variables |
| --- | --- | --- | --- |
| `staging` | none (deploys on main) | `FLY_API_TOKEN` (`fly tokens create deploy` for the staging apps), `VERCEL_TOKEN` | `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` (the staging project; `vercel link` prints both) |
| `production` | **Required reviewers: you**; deployment tags `v*` only | `FLY_API_TOKEN` (production apps), `VERCEL_TOKEN` | `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` (the production project) |
| `staging-backup`, `production-backup` | none | `BACKUP_DB_URL`, `BACKUP_R2_ACCESS_KEY_ID`, `BACKUP_R2_SECRET_ACCESS_KEY` | `BACKUP_AGE_RECIPIENT`, `BACKUP_R2_ENDPOINT`, `BACKUP_R2_BUCKET` |

In both Vercel projects: Settings > Git > disconnect automatic deployments (Actions deploys), and set the web
variables below with that environment's values.

Restoring data, rolling back and rotating secrets: [RUNBOOK.md](RUNBOOK.md).

Razorpay stays in **test mode**: the backend refuses `rzp_live_` keys unless `RAZORPAY_ALLOW_LIVE=true` (see [Going live with Razorpay](#going-live-with-razorpay)).

Placeholders below: `latestay.in` for your domain, `api.latestay.in` for the backend, `<ref>` for the Supabase project ref.

Steps 1 to 4 are done twice: once for staging (names ending `-staging`, `fly … --config fly.staging.toml`, test
keys) and once for production. The commands show production.

## 1. Database (Supabase)

1. Create a project in **Southeast Asia (Singapore)**, `ap-southeast-1`. Save the database password.
2. Don't enable PostGIS from the dashboard: migration `V1__enable_postgis.sql` runs `CREATE EXTENSION postgis` itself.
3. Connection string for the backend: **Project Settings > Database > Connection string > JDBC**.
   - **Direct connection** (preferred; Flyway and long-lived pools work best on it): `jdbc:postgresql://db.<ref>.supabase.co:5432/postgres?sslmode=require`, user `postgres`. It is IPv6-only, which Fly.io supports.
   - If the direct connection can't be reached, use the **Session pooler** (IPv4, port 5432): `jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?sslmode=require`, user `postgres.<ref>`. Don't use the transaction pooler (port 6543): Flyway and prepared statements need a session.
4. Backups: the free plan has none you can restore yourself. Before launch, either upgrade (daily backups) or schedule a `pg_dump` somewhere.

## 2. Backend (Fly.io)

For staging: `fly launch --no-deploy --copy-config --config fly.staging.toml --name latestay-api-staging --region sin`,
then the same `fly secrets set … -a latestay-api-staging` with staging's values (Razorpay `rzp_test_` keys).

```sh
cd backend
fly auth login
fly launch --no-deploy --copy-config --name latestay-api --region sin   # uses fly.toml as is
fly secrets set \
  DB_URL='jdbc:postgresql://db.<ref>.supabase.co:5432/postgres?sslmode=require' \
  DB_USER=postgres DB_PASSWORD='…' \
  WEB_ORIGIN=https://latestay.in \
  ADMIN_USERNAME=ops ADMIN_PASSWORD='…at least 16 characters…' \
  RAZORPAY_KEY_ID=rzp_test_… RAZORPAY_KEY_SECRET=… RAZORPAY_WEBHOOK_SECRET=… \
  WA_PHONE_NUMBER_ID=… WA_ACCESS_TOKEN=… WA_VERIFY_TOKEN=… WA_APP_SECRET=… \
  PHONE_VERIFICATION_SECRET="$(openssl rand -base64 48)" ADMIN_WHATSAPP_NUMBER='+91…' \
  SUPPORT_WHATSAPP_NUMBER='+91…' PUBLIC_WEB_URL=https://latestay.in \
  OWNER_SESSION_SECRET="$(openssl rand -base64 48)" \
  IMAGING_URL=http://latestay-imaging.flycast:8081 IMAGING_TOKEN='…same as the imaging app…' \
  R2_ENDPOINT=https://<account id>.r2.cloudflarestorage.com R2_BUCKET=latestay-photos \
  R2_ACCESS_KEY_ID=… R2_SECRET_ACCESS_KEY=… R2_PUBLIC_BASE_URL=https://photos.latestay.in \
  SENTRY_DSN=https://…@….ingest.sentry.io/…
fly deploy
fly certs add api.latestay.in      # then add the DNS records it prints
curl https://api.latestay.in/api/health
```

`fly.toml` keeps exactly **one machine always running**: the 21:00 availability ping, the 15-second booking jobs and the in-memory rate limits all live in the app. Don't scale to more machines without moving those first.

On every start Flyway applies new migrations before the app takes traffic. Production uses the `prod` profile (set in the Dockerfile and `fly.toml`). The local seed data and the `/api/dev/**` endpoints exist only in the `local` profile, and the backend refuses to start if `prod` is combined with `local`, the fake payment gateway, a non-https `WEB_ORIGIN` or `PUBLIC_WEB_URL`, a short admin password, or a short `PHONE_VERIFICATION_SECRET` or `OWNER_SESSION_SECRET`.

Production has no hotels until you add them: use the admin panel (`https://latestay.in/admin`, Hotels tab) or `POST /api/admin/hotels`.

### Backend environment variables

| Variable | Required | Example | Where it comes from / what it does |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | yes (set by Dockerfile and fly.toml) | `prod` | JSON logs, production safety checks, API docs off |
| `PORT` | yes (set) | `8080` | Port the app listens on; matches `internal_port` in fly.toml |
| `DB_URL` | yes | `jdbc:postgresql://db.<ref>.supabase.co:5432/postgres?sslmode=require` | Supabase, step 1 |
| `DB_USER` | yes | `postgres` (direct) or `postgres.<ref>` (pooler) | Supabase |
| `DB_PASSWORD` | yes | | Supabase project database password |
| `WEB_ORIGIN` | yes | `https://latestay.in` | Exact web origins allowed by CORS, comma-separated, https only, no wildcards. Add `https://www.latestay.in` if you serve both |
| `ADMIN_USERNAME` | for the admin panel | `ops` | HTTP basic for `/api/admin/**`. Leave either admin variable empty to disable admin entirely |
| `ADMIN_PASSWORD` | for the admin panel | 16+ characters | Generate one (`openssl rand -base64 24`) |
| `RAZORPAY_KEY_ID` | yes | `rzp_test_…` | Razorpay Dashboard (Test Mode) > Account & Settings > API Keys |
| `RAZORPAY_KEY_SECRET` | yes | | Same page, shown once |
| `RAZORPAY_WEBHOOK_SECRET` | yes | | The secret you type when adding the webhook (step 4) |
| `RAZORPAY_ALLOW_LIVE` | no | `false` | Must be `true` before live keys (`rzp_live_`) are accepted |
| `WA_PHONE_NUMBER_ID` | for WhatsApp | `106540352242922` | Meta app > WhatsApp > API setup. Without it messages are logged as NOT_SENT |
| `WA_ACCESS_TOKEN` | for WhatsApp | | A **system user** token with `whatsapp_business_messaging` (temporary tokens expire after 24 hours) |
| `WA_VERIFY_TOKEN` | for WhatsApp | any random string | Entered again in the Meta webhook settings |
| `WA_APP_SECRET` | for WhatsApp | | Meta app > App settings > Basic > App secret |
| `WA_GRAPH_VERSION` | no | `v23.0` | Graph API version |
| `LLM_PROVIDER` | no | `anthropic`, `openai` or `none` | Without it (or without a key) the chat uses the deterministic parser and templates |
| `LLM_MODEL` | no | `claude-haiku-5-5` | Default for `anthropic`; required for `openai` |
| `LLM_API_KEY` | with `LLM_PROVIDER` | | Provider key |
| `LLM_BASE_URL` | no | | Only for a custom OpenAI-compatible server |
| `PHONE_VERIFICATION_SECRET` | yes | 48 random characters | Key for the phone and code hashes and the "verified on this device" cookie (`openssl rand -base64 48`). At least 32 characters; production refuses to start otherwise. Changing it signs everyone out of "verified" |
| `PHONE_COOKIE_SECURE` | no | `true` | Leave `true` (https only); `false` only for local http |
| `ADMIN_WHATSAPP_NUMBER` | recommended | `+91 98765 43210` | Where "guest turned away" and dispute alerts go (template `admin_alert`). Empty = alerts are only logged |
| `SUPPORT_WHATSAPP_NUMBER` | recommended | `+91 98765 43210` | Linked from the traveller's `booking_confirmed` message (wa.me). Empty = a link to /contact |
| `PUBLIC_WEB_URL` | yes | `https://latestay.in` | The web app's address for links in WhatsApp messages: booking passes and owner sign-in links. https only in production |
| `DISPUTE_HIDE_HOURS` | no | `6` | How long "the hotel turned me away" hides the hotel from search, unless the admin presses Keep hidden |
| `OWNER_SESSION_SECRET` | yes | 48 random characters | Signs the owner page's `ls_owner` cookie (`openssl rand -base64 48`). At least 32 characters; production refuses to start otherwise. Changing it signs every owner out |
| `OWNER_COOKIE_SECURE` | no | `true` | Leave `true`; `false` only for local http |
| `IMAGING_URL` | for photo uploads | `http://latestay-imaging.flycast:8081` | The imaging app on Fly's private network (section 2b) |
| `IMAGING_TOKEN` | for photo uploads | 32 random characters | Shared with the imaging app (`openssl rand -hex 32`) |
| `R2_ENDPOINT` | for photo uploads | `https://<account id>.r2.cloudflarestorage.com` | Cloudflare > R2 > Overview > account id (section 2c) |
| `R2_BUCKET` | for photo uploads | `latestay-photos` | Empty = uploads answer "photo storage isn't set up"; existing photo URLs keep working |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | for photo uploads | | An R2 API token with Object Read & Write on that bucket only |
| `R2_PUBLIC_BASE_URL` | for photo uploads | `https://photos.latestay.in` | The bucket's public custom domain (or its r2.dev URL for testing). Same value as the web's `NEXT_PUBLIC_PHOTO_BASE_URL` |
| `R2_REGION` / `R2_PATH_STYLE` | no | `auto` / `false` | Leave as is for R2 |
| `SENTRY_DSN` | no | `https://…@….ingest.sentry.io/…` | Sentry > Project (Java / Spring Boot) > Client Keys. Empty = off |
| `SENTRY_ENVIRONMENT` | no | `production` | Set in fly.toml |
| `BOOKING_DEPOSIT_CAP` | no | `200` | Rupees paid online at most |
| `BOOKING_COMMISSION` | no | `100` | LateStay's cut in rupees, taken from the deposit |
| `JOBS_ENABLED` | no | `true` | Turns the scheduled jobs off when `false`. With several machines it can stay on: ShedLock (table `shedlock`) runs each job once |
| `APP_ENV` | yes (set in fly.toml / fly.staging.toml) | `production` / `staging` | Production never seeds data; staging refuses live Razorpay keys. Anything else is refused with the prod profile |
| `SEED_DATA` | no | `false` | Seeded hotels; on by the staging and local profiles, refused in production |
| `LLM_PRICE_INPUT_PER_MTOK_USD` / `LLM_PRICE_OUTPUT_PER_MTOK_USD` | with an LLM | from the provider's price page | Dollars per million tokens, to record each bot turn's cost. 0 = cost not recorded (logged at start) |
| `LLM_MONTHLY_BUDGET_USD` | recommended | `30` | Once this month's recorded spend reaches it, replies come from the templates until the 1st (prices never depend on the LLM). Also set a limit on the key at the provider |
| `USD_INR_RATE` | no | `88` | For LLM cost in rupees (digest, /admin/today, /admin/funnel). Update now and then |
| `POSTHOG_API_KEY` | no | `phc_…` | PostHog > Project settings > Project API key. Events are always kept in the database; with a key they're also forwarded from the server. In PostHog, also turn on Project settings > "Discard client IP data" |
| `POSTHOG_HOST` | no | `https://eu.i.posthog.com` | EU cloud by default |
| `PRICING_VERSION` | no | `2` | `2`: opening offers and concessions follow the night's occupancy, weekend and season (docs/ARCHITECTURE.md#pricing). `1`: the rules before that, a switch back without a deploy of code. Floors are the same either way. See sim/reports/pricing-compare.md before choosing |
| `PRICING_WEEKEND_PERCENT` | no | `110` | Friday and Saturday nights open at this % (with any season, 70–150% in all). `100` turns it off |
| `RELIABILITY_MIN_DECISIONS` | no | `3` | A hotel's confirm rate counts towards pausing once it has answered (or missed) this many bookings in 30 days |
| `RELIABILITY_MIN_CONFIRM_RATE` | no | `70` | Pause a hotel from search below this confirm rate (%), until an admin reviews it |
| `RELIABILITY_MAX_STRIKES` | no | `2` | Pause at this many rescues plus upheld disputes in 30 days |
| `WA_ROOMS_TEMPLATE` | no | `false` | `true` once `booking_request_rooms` is approved: two-room bookings use it. Until then they go out as `booking_request`, which gives only the number of guests |

## 2b. Imaging (Fly.io, private)

The photo processor has no public address: only the backend calls it, over Fly's private network.

```sh
cd imaging
fly launch --no-deploy --copy-config --name latestay-imaging --region sin
fly secrets set IMAGING_TOKEN="$(openssl rand -hex 32)"   # set the same value on latestay-api
fly ips allocate-v6 --private                              # a Flycast address: latestay-imaging.flycast
fly deploy
```

It stops when idle and starts on the next upload (a few seconds). sharp's prebuilt libvips can't decode HEIC (HEVC), so HEIC uploads get a clear message instead; picking photos in the admin panel on an iPhone hands over JPEGs anyway.

## 2c. Photo storage (Cloudflare R2)

1. Cloudflare > R2 > **Create bucket** `latestay-photos` (location: automatic).
2. Bucket > Settings > **Custom domain**: `photos.latestay.in` (or enable the r2.dev URL for testing only; it is rate-limited).
3. R2 > **Manage API tokens** > Create token: Object Read & Write, this bucket only. Put the key pair in `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY`.
4. Files are written with `Cache-Control: public, max-age=31536000, immutable` (each upload gets a new key), so the CDN keeps them.

Locally, `docker compose up imaging photos` runs the imaging service on 8081 and an S3Mock bucket on 9090; `backend/.env.example` points at both.

## 3. Web (Vercel)

1. Import the GitHub repository. **Root directory: `web`**, framework preset Next.js. `web/vercel.json` puts the functions in `sin1`.
2. Add the environment variables below for Production (and Preview if you use previews), then deploy.
3. **Domains:** add `latestay.in`; make sure `WEB_ORIGIN` on the backend lists exactly the origin(s) you serve. Preview deployments get their own URLs; to let one talk to the production API, add its exact URL to `WEB_ORIGIN` (or point previews at a separate staging backend).

`NEXT_PUBLIC_*` values are baked into the browser bundle at build time: redeploy after changing them.

### Web environment variables

| Variable | Required | Example | Where it comes from / what it does |
| --- | --- | --- | --- |
| `NEXT_PUBLIC_API_URL` | yes | `https://api.latestay.in` | The backend. Also used by the service worker to decide what to cache |
| `NEXT_PUBLIC_MAP_TILE_URL` | yes for production | `https://api.maptiler.com/maps/streets-v2/256/{z}/{x}/{y}.png?key=…` | A tile provider (MapTiler, Stadia Maps and others have free tiers). Empty falls back to OpenStreetMap's own servers, whose usage policy doesn't allow app traffic at scale. Restrict the key to your domain in the provider's dashboard |
| `NEXT_PUBLIC_MAP_ATTRIBUTION` | with a tile URL | `&copy; MapTiler &copy; OpenStreetMap contributors` | The credit line your provider requires (HTML) |
| `NEXT_PUBLIC_SUPPORT_EMAIL` | yes | `help@latestay.in` | Shown on /privacy, /terms, /refund-policy and /contact |
| `SUPPORT_WHATSAPP_NUMBER` | yes | `+91 98765 43210` | LateStay support on WhatsApp: the pass's "Problem at the hotel?" link and /contact. Server-side only |
| `NEXT_PUBLIC_BUSINESS_ADDRESS` | for Razorpay live | `LateStay, 12 Mall Road,\nManali 175131, Himachal Pradesh` | Registered address on /contact (Razorpay checks it during live activation) |
| `NEXT_PUBLIC_APP_ENV` | yes | `production` / `staging` | `staging` shows the "Staging · test payments" bar on every page. Only `production` lets search engines in: anything else (including empty) serves a robots.txt that disallows everything and an empty sitemap |
| `NEXT_PUBLIC_SITE_URL` | yes | `https://latestay.in` | The site's own address: canonical URLs, sitemap.xml, robots.txt and the town pages' Open Graph images |
| `NEXT_PUBLIC_PHOTO_BASE_URL` | for uploaded photos | `https://photos.latestay.in` | Same as the backend's `R2_PUBLIC_BASE_URL`; allowed in next/image's remote patterns |
| `NEXT_PUBLIC_SENTRY_DSN` | no | | Sentry > Project (Next.js) > Client Keys. Browser errors; empty = off |
| `NEXT_PUBLIC_SENTRY_ENVIRONMENT` | no | `production` | |
| `SENTRY_DSN` | no | same as above | Server-side (rendering) errors |
| `SENTRY_ENVIRONMENT` | no | `production` | |

## 4. Webhooks

**Razorpay** (Test Mode > Account & Settings > Webhooks > Add new webhook):

- URL: `https://api.latestay.in/api/webhooks/razorpay`
- Secret: the value of `RAZORPAY_WEBHOOK_SECRET`
- Events: `payment.captured`, `refund.processed`

**WhatsApp** (Meta app > WhatsApp > Configuration > Webhook):

- Callback URL: `https://api.latestay.in/api/webhooks/whatsapp`, verify token: `WA_VERIFY_TOKEN`, then **Verify and save**
- Subscribe to the `messages` field
- Submit the ten templates in [whatsapp-templates.md](whatsapp-templates.md) and wait for approval. Without `phone_otp` nobody can verify a number, so nobody can book

## 5. Checks after a deploy

```sh
curl -s https://api.latestay.in/api/health                       # {"status":"ok","postgis":"3.5 …"}
curl -s -o /dev/null -w '%{http_code}\n' https://api.latestay.in/v3/api-docs   # 404 in production
curl -s -X OPTIONS -H 'Origin: https://evil.example' -H 'Access-Control-Request-Method: GET' \
  -o /dev/null -w '%{http_code}\n' https://api.latestay.in/api/search             # 403
fly logs -a latestay-api | head                                 # one JSON object per line, phones masked
```

Then in a browser: search, open a hotel, haggle, pay with a Razorpay [test card or test UPI ID](https://razorpay.com/docs/payments/payments/test-card-details/), confirm from the admin panel (or the WhatsApp button) and check the pass shows the code. Install the app from the browser menu and open your pass in airplane mode.

Lighthouse against the live site (mobile, simulated Slow 4G, the default):

```sh
npx lighthouse https://latestay.in/ --only-categories=performance,accessibility --view
```

## 6. Rolling back (details in [RUNBOOK.md](RUNBOOK.md#rolling-back-a-bad-deploy))

- **Web:** Vercel > Deployments > pick the previous one > Promote to Production (instant).
- **Backend:** `fly releases -a latestay-api`, then `fly deploy --image registry.fly.io/latestay-api:<previous version tag>`.
- **Database:** migrations only ever move forward. A release whose migration must be undone needs a new migration that reverses it; restore from a backup only as a last resort.

## Going live with Razorpay

Razorpay's live activation reviews the site's policy pages: `/terms`, `/privacy`, `/refund-policy` and `/contact` (set `NEXT_PUBLIC_BUSINESS_ADDRESS` and `SUPPORT_WHATSAPP_NUMBER` first).

After KYC: create live keys, set `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` to them, add a live-mode webhook with the same URL and events (it has its own secret: update `RAZORPAY_WEBHOOK_SECRET`), and set `RAZORPAY_ALLOW_LIVE=true`. Without that last variable the backend refuses to start with live keys.

## CI

`.github/workflows/ci.yml` runs on every push and pull request: backend tests (Testcontainers on the runner's Docker; the live LLM tests tagged `llm` are excluded by `./gradlew test`) and web lint, typecheck, unit tests and build. It needs no secrets.
