# Deploying LateStay

Three services, all in or next to Singapore, the closest region these providers offer to India (none of Fly.io, Railway or Render has an Indian region; Vercel and Supabase do have Mumbai, but they should sit next to the backend):

| Service | Provider | Region | What runs there |
| --- | --- | --- | --- |
| Backend | Fly.io | `sin` (Singapore) | `backend/Dockerfile`: Spring Boot, Flyway migrations on start, the booking and WhatsApp jobs |
| Database | Supabase (Postgres 16 + PostGIS) | `ap-southeast-1` (Singapore) | Everything in `backend/src/main/resources/db/migration` |
| Web | Vercel | `sin1` (Singapore) functions, global CDN | `web/` (Next.js) |

Razorpay stays in **test mode**: the backend refuses `rzp_live_` keys unless `RAZORPAY_ALLOW_LIVE=true` (see [Going live with Razorpay](#going-live-with-razorpay)).

Placeholders below: `latestay.in` for your domain, `api.latestay.in` for the backend, `<ref>` for the Supabase project ref.

## 1. Database (Supabase)

1. Create a project in **Southeast Asia (Singapore)**, `ap-southeast-1`. Save the database password.
2. Don't enable PostGIS from the dashboard: migration `V1__enable_postgis.sql` runs `CREATE EXTENSION postgis` itself.
3. Connection string for the backend: **Project Settings > Database > Connection string > JDBC**.
   - **Direct connection** (preferred; Flyway and long-lived pools work best on it): `jdbc:postgresql://db.<ref>.supabase.co:5432/postgres?sslmode=require`, user `postgres`. It is IPv6-only, which Fly.io supports.
   - If the direct connection can't be reached, use the **Session pooler** (IPv4, port 5432): `jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?sslmode=require`, user `postgres.<ref>`. Don't use the transaction pooler (port 6543): Flyway and prepared statements need a session.
4. Backups: the free plan has none you can restore yourself. Before launch, either upgrade (daily backups) or schedule a `pg_dump` somewhere.

## 2. Backend (Fly.io)

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
  SENTRY_DSN=https://…@….ingest.sentry.io/…
fly deploy
fly certs add api.latestay.in      # then add the DNS records it prints
curl https://api.latestay.in/api/health
```

`fly.toml` keeps exactly **one machine always running**: the 21:00 availability ping, the 15-second booking jobs and the in-memory rate limits all live in the app. Don't scale to more machines without moving those first.

On every start Flyway applies new migrations before the app takes traffic. Production uses the `prod` profile (set in the Dockerfile and `fly.toml`). The local seed data and the `/api/dev/**` endpoints exist only in the `local` profile, and the backend refuses to start if `prod` is combined with `local`, the fake payment gateway, a non-https `WEB_ORIGIN` or a short admin password.

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
| `SENTRY_DSN` | no | `https://…@….ingest.sentry.io/…` | Sentry > Project (Java / Spring Boot) > Client Keys. Empty = off |
| `SENTRY_ENVIRONMENT` | no | `production` | Set in fly.toml |
| `BOOKING_DEPOSIT_CAP` | no | `200` | Rupees paid online at most |
| `BOOKING_COMMISSION` | no | `100` | LateStay's cut in rupees, taken from the deposit |
| `JOBS_ENABLED` | no | `true` | Turns the scheduled jobs off when `false` (for a second, read-only instance only) |

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
| `NEXT_PUBLIC_SUPPORT_EMAIL` | yes | `help@latestay.in` | Shown on /privacy and /terms |
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
- Submit the three templates in [whatsapp-templates.md](whatsapp-templates.md) and wait for approval

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

## 6. Rolling back

- **Web:** Vercel > Deployments > pick the previous one > Promote to Production (instant).
- **Backend:** `fly releases -a latestay-api`, then `fly deploy --image registry.fly.io/latestay-api:<previous version tag>`.
- **Database:** migrations only ever move forward. A release whose migration must be undone needs a new migration that reverses it; restore from a backup only as a last resort.

## Going live with Razorpay

After KYC: create live keys, set `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` to them, add a live-mode webhook with the same URL and events (it has its own secret: update `RAZORPAY_WEBHOOK_SECRET`), and set `RAZORPAY_ALLOW_LIVE=true`. Without that last variable the backend refuses to start with live keys.

## CI

`.github/workflows/ci.yml` runs on every push and pull request: backend tests (Testcontainers on the runner's Docker; the live LLM tests tagged `llm` are excluded by `./gradlew test`) and web lint, typecheck, unit tests and build. It needs no secrets.
