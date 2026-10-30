# LateStay

Travellers who reach Indian hill stations late at night haggle with an AI agent (acting for the hotel) for a room for part of the night.

- `backend/`: Spring Boot 3.5, Java 21, Gradle
- `web/`: Next.js traveller PWA (plus `/admin` and the hotel owner's `/owner` page)
- `imaging/`: photo processor (Node + sharp) for admin uploads; `docker compose up imaging photos` runs it with a local S3 bucket
- `sim/`: negotiation simulator (Python 3.12, uv); see [sim/README.md](sim/README.md). `make sim-offline` runs it without an API key
- `docs/`: [DESIGN.md](docs/DESIGN.md), [ARCHITECTURE.md](docs/ARCHITECTURE.md), [DEPLOY.md](docs/DEPLOY.md) (production: Fly.io, Supabase, Vercel, every env var), [whatsapp-templates.md](docs/whatsapp-templates.md)

## Prerequisites

- Java 21
- Node 20+ and npm
- Docker (Docker Desktop on macOS)
- [uv](https://docs.astral.sh/uv/) for the simulator

Gradle comes with the repo through the wrapper (`backend/gradlew`).

## 1. Env files

```sh
cp .env.example .env                  # docker compose (Postgres)
cp backend/.env.example backend/.env  # backend
cp web/.env.example web/.env.local    # web
```

Real env files are git-ignored. When you add a variable, add it to the matching `.env.example` too.

## Production

See [docs/DEPLOY.md](docs/DEPLOY.md). In short: the backend runs from `backend/Dockerfile` with `SPRING_PROFILES_ACTIVE=prod` (JSON logs with phone numbers masked, Sentry when `SENTRY_DSN` is set, no seed data or dev endpoints, and it refuses to start with development settings or live Razorpay keys), the database is Supabase with PostGIS, and the web app is on Vercel. Rate limits: 20 chat messages a minute per negotiation and per IP; messages are at most 500 characters. Traveller phone numbers are deleted 90 days after the stay (nightly job). CI (`.github/workflows/ci.yml`) runs the backend tests and the web lint, typecheck, unit tests and build on every push and pull request.

## 2. Database

```sh
docker compose up -d --wait
```

This runs Postgres 16 + PostGIS on `localhost:5432` (db/user/password `latestay`) with data in the `latestay-pgdata` volume. The `postgis/postgis` image has no arm64 build, so on Apple Silicon it runs under emulation (`platform: linux/amd64`).

If port 5432 is already taken (for example by a Homebrew Postgres), set `POSTGRES_PORT=5434` in `.env` and update `DB_URL` in `backend/.env` to use the same port.

## 3. Backend

```sh
cd backend
set -a; source .env; set +a
./gradlew bootRun
```

Runs on `http://localhost:8080`. Flyway applies migrations on startup. Check it with:

```sh
curl localhost:8080/api/health
# {"status":"ok","postgis":"3.5 USE_GEOS=1 USE_PROJ=1 USE_STATS=1"}
```

`SPRING_PROFILES_ACTIVE=local` loads `application-local.yml`, which falls back to the compose DB defaults when the DB variables are unset.

### Search, negotiate, administer

With the `local` profile the backend seeds 13 fictional hotels around Manali (Mall Road, Old Manali) and Kasol, and reports their availability for tonight and tomorrow on every start. Placeholder photos live in `web/public/seed/`.

```sh
# Hotels within 10 km with rooms tonight (times are local, Asia/Kolkata)
curl "localhost:8080/api/search?lat=32.2432&lng=77.1892&arrive=2026-10-28T23:30&leave=2026-10-29T09:00&guests=2&filters=heater,parking"
# -> {stayRequestId, nightOf, slotType, results: [{id, name, photos, amenities, distanceKm, roomsFree, rating, fromPrice, roomTypes}]}

# Open a negotiation for one of the room types, then chat
curl -X POST localhost:8080/api/negotiations -H 'Content-Type: application/json' \
  -d '{"stayRequestId":"<id>","roomTypeId":"<id>"}'
curl -X POST localhost:8080/api/negotiations/<id>/messages -H 'Content-Type: application/json' -d '{"text":"800 mein de do"}'

# Admin (HTTP basic, ADMIN_USERNAME / ADMIN_PASSWORD from backend/.env; admin is off when either is empty)
curl -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" localhost:8080/api/admin/hotels
```

Filters: `heater`, `parking`, `hot_water`, `late_check_in`, `near_bus_stand`, `couple_friendly`, `local_id_ok`. An arrival up to 09:00 (any early-morning arrival) belongs to the previous date's night. Admin endpoints: `/api/admin/hotels` (CRUD; DELETE deactivates), `/api/admin/hotels/{id}/room-types`, `/api/admin/room-types/{id}/floor-rules`, `PUT /api/admin/hotels/{id}/availability/{nightOf}`.

### Bookings and payments

An agreed negotiation becomes a booking: a deposit of min(₹200, agreed price) is paid online through Razorpay, the rest at the desk. The hotel (for now, an admin at `/admin`) confirms within 5 minutes or the deposit is refunded automatically. The traveller's pass at `/pass/<bookingId>` shows the 4-digit desk code once confirmed. There is no traveller login: the booking's id is the link, and the web app remembers ids in `localStorage`.

```
PENDING_PAYMENT -> DEPOSIT_PAID -> HOTEL_CONFIRMED -> CHECKED_IN -> COMPLETED
PENDING_PAYMENT -> EXPIRED (unpaid after 10 min) | CANCELLED
DEPOSIT_PAID    -> HOTEL_DECLINED | HOTEL_TIMEOUT (5 min) | CANCELLED -> REFUNDED
HOTEL_CONFIRMED -> arrival check to the owner 60 min after arrive-by:
                   Arrived / the code -> CHECKED_IN, Didn't arrive -> NO_SHOW, no reply by 10:00 -> COMPLETED
NO_SHOW or "turned away" -> DISPUTED -> settled by an admin; admin Rescue -> HOTEL_FAILED (refund)
```

Agreeing a price holds the room for 10 minutes (no double booking of the last room). Before paying, the traveller verifies their number with a WhatsApp code; with the local profile and no WhatsApp keys, read it from `GET /api/dev/phone-verifications/latest?phone=...`. All states are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#booking-states).

Razorpay test mode: set `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` in `backend/.env`. In the Razorpay dashboard (Test Mode), add a webhook for `payment.captured` and `refund.processed` pointing at `https://<public url>/api/webhooks/razorpay` (for local work, through a tunnel such as `cloudflared` or `ngrok`). Without keys, the local profile uses a fake gateway: the web app completes the payment through `POST /api/dev/payments/<orderId>/pay` and refunds are processed at once.

Admin panel: `http://localhost:3000/admin`, signing in with `ADMIN_USERNAME` / `ADMIN_PASSWORD`. Bookings board (confirm, decline, check in with the guest's code), live negotiations with full transcripts, and an editor for hotels, room types, floor rules and tonight's free rooms.

### Hotel owners on WhatsApp

Owners use WhatsApp only (WhatsApp Business Cloud API). At 21:00 India time every active hotel gets the `nightly_availability` template ("How many rooms are free tonight?", buttons 0 / 1 / 2 / 3+); owners can also just type `rooms 2` or `2 room khali`. **A hotel that hasn't answered for tonight doesn't show in search.** When a deposit is paid the owner gets `booking_request` with Confirm / Can't take it buttons; no answer within 5 minutes refunds the traveller. Every message in and out is stored in `whatsapp_message` and shown per hotel in the admin panel (Hotels tab), which also has "Send availability ping now".

An owner is recognised by the hotel's `whatsapp_number` (with country code, e.g. `+91 98765 43210`); messages from other numbers are logged and ignored. The templates must be approved by Meta first: their exact text, category and buttons are in [docs/whatsapp-templates.md](docs/whatsapp-templates.md).

Set up (Meta developer account, a Meta app with the WhatsApp product):

1. In `backend/.env` set `WA_PHONE_NUMBER_ID` and `WA_ACCESS_TOKEN` (WhatsApp > API setup), `WA_APP_SECRET` (App settings > Basic) and `WA_VERIFY_TOKEN` (any string you choose).
2. Expose the local backend over HTTPS with a tunnel:
   ```sh
   cloudflared tunnel --url http://localhost:8080   # prints https://<random>.trycloudflare.com
   # or
   ngrok http 8080                                   # prints https://<random>.ngrok-free.app
   ```
3. In the Meta app, WhatsApp > Configuration > Webhook: callback URL `https://<tunnel host>/api/webhooks/whatsapp`, verify token = `WA_VERIFY_TOKEN`, then **Verify and save** (Meta calls the GET endpoint, which echoes `hub.challenge`). Subscribe to the `messages` field.
4. Add your own phone as a test recipient (API setup > To), put that number on a seeded hotel in the admin panel, and press "Send availability ping now". Tap a button or reply `rooms 2` from your phone; the reply and the acknowledgement appear in the hotel's WhatsApp log.

Without the `WA_*` variables nothing is sent: outbound messages are logged as NOT_SENT. With the local profile you can play the owner without Meta:

```sh
curl -X POST localhost:8080/api/dev/whatsapp/inbound -H 'Content-Type: application/json' \
  -d '{"from":"+91 90000 00001","text":"2 room khali hai"}'
# a button tap: pass the payload shown in the queued template, e.g. "BOOKING|<booking id>|CONFIRM"
curl -X POST localhost:8080/api/dev/whatsapp/inbound -H 'Content-Type: application/json' \
  -d '{"from":"+91 90000 00001","buttonPayload":"BOOKING|<booking id>|CONFIRM","buttonText":"Confirm"}'
```

The quick tunnel's URL changes every time it starts; update the callback URL in the Meta app when it does.

**Owner page.** An owner who sends `hisaab` (or `account`, `earnings`) gets a sign-in link valid once for 15 minutes; it opens `/owner` (tonight's guests, rooms free, earnings and payouts, lowest prices) for 30 days on that phone. The WhatsApp log shows sent links redacted, so locally get one from the dev endpoint (local profile only) and open it:

```sh
curl -X POST 'localhost:8080/api/dev/owner-links?hotel=<hotel slug>'   # {"url":"http://localhost:3000/owner/login?t=…"}
```

Weekly settlements are made on Mondays at 10:00 India time (admin panel, Payouts tab: mark paid with the UPI reference, download the CSV).

### Dev negotiation API (local profile only)

For trying the engine with arbitrary prices (the simulator uses it). Sessions are stored like real ones, under an inactive "Simulator Lodge" that never shows in search.

```sh
curl -X POST localhost:8080/api/dev/negotiations -H 'Content-Type: application/json' \
  -d '{"listPrice":1400,"floorPrice":700,"slotType":"LATE_NIGHT","arrival":"23:30"}'
# -> session with "id" and opening offer 1000

curl -X POST localhost:8080/api/dev/negotiations/<id>/turns -H 'Content-Type: application/json' \
  -d '{"type":"COUNTER","amount":500}'
# type: COUNTER (needs amount) | ACCEPT | ASK_BEST_PRICE | OTHER
```

### Traveller chat (local profile only)

`POST /api/negotiations/<id>/messages` with `{"text": "700 mein de do bhai"}` returns `{reply, offer, status, reasonCode, intent, replySource, llmUsage}`. Every turn is stored in `negotiation_turn`. Hotel facts come from the hotel's amenities and policies.

The LLM only reads the traveller's intent and words the reply. Prices always come from the negotiation engine, and every reply is checked so it mentions only the engine's price; otherwise a fixed template is used. Configure it in `backend/.env`:

| Variable | Values |
| --- | --- |
| `LLM_PROVIDER` | `anthropic`, `openai` (any OpenAI-compatible server) or `none` (default) |
| `LLM_MODEL` | defaults to `claude-haiku-5-5` for `anthropic`; required for `openai` |
| `LLM_API_KEY` | provider key; when empty, everything runs on the deterministic parser and templates |
| `LLM_BASE_URL` | optional API base URL override |

Tests (Testcontainers starts its own PostGIS container, so Docker must be running):

```sh
./gradlew test       # everything except live LLM tests
./gradlew llmTest    # live LLM tests only; needs LLM_PROVIDER and LLM_API_KEY, skipped otherwise
```

## 4. Web

```sh
cd web
npm install
npm run dev
```

Open `http://localhost:3000`.

The web app is an installable PWA (manifest, icons in `public/icons/`, generated by `node scripts/make-icons.mjs`). Its service worker (Serwist, `src/app/sw.ts`) is off in `npm run dev`; use `npm run build && npm start` to try it. It keeps pages you've opened, your last search and your booking passes for offline use, and shows `/~offline` for anything else. Pages fetch their first data on the server, so the first hotel photo is in the HTML.

Checks:

```sh
npm test
npm run lint
npm run build
# Screenshots of every page at 390 and 1280 px into screenshots/; needs the local backend, and the admin
# credentials in the environment:
(set -a; source ../backend/.env; set +a; unset PORT; npm run screenshots)
```
