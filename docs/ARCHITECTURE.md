# Architecture

LateStay matches travellers who reach Indian hill stations late at night with hotels that still have rooms, and lets them haggle for part of the night with an AI agent acting for the hotel.

## Components

- **Traveller PWA (`web/`).** Next.js App Router, mobile-first. Search, hotel pages, the haggle chat and the booking pass. Talks only to the backend's `/api/**`.
- **iOS and Android apps (`mobile/`).** Capacitor shells that load the same web app and add native features around it ([Mobile apps](#mobile-apps), [MOBILE.md](MOBILE.md)).
- **Backend (`backend/`).** Spring Boot 3, Java 21. Modules:
  - **Matching:** finds hotels near the traveller with rooms free tonight (PostGIS distance queries).
  - **Negotiation engine:** deterministic pricing rules per hotel (floor, opening price, concession steps, time-of-night, and the night's occupancy, weekend and season: [Pricing](#pricing)). The LLM only phrases messages and interprets traveller intent; it never decides a price. Every number it says comes from the engine.
  - **Bookings:** holds, confirmations, the 4-digit booking pass code, check-in windows.
  - **Payments:** collects the deposit through Razorpay; an append-only ledger and weekly settlements pay hotels by UPI.
- **Imaging (`imaging/`).** A small Node service with sharp, private to the backend: an admin's JPEG or PNG upload in, EXIF-free WebP at 400, 800 and 1600 px out. It stores nothing.
- **Hotel owners (WhatsApp, plus one page).** Owners have no app or password. Through the WhatsApp Business Cloud API they answer a 21:00 availability ping (a hotel that hasn't answered for the night is hidden from search) and confirm or decline paid bookings with template buttons; the 5-minute confirmation timeout refunds the traveller if they don't. Replies are parsed with fixed rules (button payloads, then "rooms 2" / "2 room khali" style text), never an LLM: 1 to 3 digits are a room count (0 to 50 accepted), 4 digits are a desk code only if they match an active booking at that owner's hotel, anything else gets a short help reply. Sending "hisaab", "account" or "earnings" gets a sign-in link to the owner page (below). Webhooks are verified with X-Hub-Signature-256 and deduplicated by WhatsApp message id; outbound messages go through an outbox table (`whatsapp_message`), so no Graph API call runs inside a booking transaction. Templates: docs/whatsapp-templates.md.
- **Database.** Postgres 16 with PostGIS, migrated with Flyway. `hotel` (geography point, amenities, policies), `hotel_photo` (ordered, the first is the cover; uploads keep a storage key for their WebP variants), `room_type`, `floor_rule` (per slot, weekday mask, season, min hours), `nightly_availability` (per hotel and night; an arrival up to 09:00 belongs to the previous night), `stay_request` (each search), `booking` / `booking_event` / `payment` (deposits through Razorpay), `whatsapp_message` (every WhatsApp message in and out, with its audience: owner, traveller or admin), `ledger_entry` / `settlement` / `settlement_line` (what LateStay owes each hotel, in paise), `owner_login_token` / `owner_session` (owner page sign-in), `negotiation_session` (state plus a snapshot of the pricing policy it opened with) and `negotiation_turn` (every message, with intent, decision and validator result). Search uses `ST_DWithin` / `ST_Distance` within 10 km; "from" prices are the engine's opening offers. Results are ranked by distance band (under 1 km, 1–3 km, 3 km and more), then reliability score, then price ([Ratings and reliability](#ratings-and-reliability)).
- **Persistence boundary.** The engine stays pure Java. Sessions are saved as snapshots and rebuilt with `NegotiationEngine.restore`; the database layer (`com.latestay.session`) wraps it, and each message runs in one transaction holding a row lock on its session.
- **Simulator (`sim/`).** Simulated travellers (LLM or scripted personas) haggle through the public API and report deal rates, floor violations and reply-number mismatches.

```
Traveller (PWA) ──HTTPS──> Spring Boot backend <──WhatsApp──> Hotel owners
                                 │
                         Postgres + PostGIS
```

## Booking states

```
PENDING_PAYMENT -> DEPOSIT_PAID -> HOTEL_CONFIRMED -> CHECKED_IN -> COMPLETED
PENDING_PAYMENT -> EXPIRED (unpaid 10 min) | CANCELLED
DEPOSIT_PAID    -> HOTEL_DECLINED | HOTEL_TIMEOUT (5 min) | CANCELLED (full refund) -> REFUNDED
HOTEL_CONFIRMED -> CHECKED_IN (owner: Arrived, or types the code)
                -> NO_SHOW (owner: Didn't arrive; deposit kept)
                -> COMPLETED (nobody answered the arrival check by 10:00 next morning, at least 2 h after it)
                -> DISPUTED (traveller: "the hotel turned me away", from the arrival time)
NO_SHOW         -> DISPUTED (traveller: "I did arrive", once) | CHECKED_IN (owner types the code)
DISPUTED        -> COMPLETED | NO_SHOW (admin settles)
any paid state  -> HOTEL_FAILED (admin Rescue: immediate refund, counts against the hotel)
```

- **CHECKED_IN:** the guest is at the hotel. COMPLETED follows at the leave time.
- **COMPLETED:** the stay happened, or nobody said otherwise. The hotel keeps the deposit and collects the rest at the desk.
- **NO_SHOW:** the hotel says the guest never came. The deposit stays with the hotel unless a dispute goes the guest's way.
- **DISPUTED:** the traveller disagrees with the hotel. With "turned away", the room goes back and the hotel is hidden from search for that night for `DISPUTE_HIDE_HOURS` (default 6), or until the dispute is settled if an admin presses **Keep hidden**; the admin gets a WhatsApp alert and can settle at any time.
- **CANCELLED:** the traveller cancelled before the hotel confirmed (refunded in full if paid).
- **HOTEL_FAILED:** an admin's Rescue. The deposit is refunded at once, the hotel's `reliability_failures` goes up, and the traveller's pass lists the three nearest hotels with rooms for a search arriving now. Terminal; the refund is tracked on the payment.

Every move goes through one guarded transition table (`BookingTransitions`) under a row lock and is logged in `booking_event`.

**Rooms.** Agreeing a price takes a 10-minute `room_hold` (the night's `nightly_availability` row is locked, so two travellers can't take the last room), and the booking takes the hold over. Rooms available = the owner's latest report − active holds − live bookings made after that report (SQL function `rooms_available`). Search, the hotel page, negotiations and bookings all use it.

**Phone verification.** Before payment the traveller's number is verified with a WhatsApp authentication template (6 digits, 5 minutes, 3 attempts, limited per number and per IP). Only HMAC hashes are stored. A signed, httpOnly cookie remembers a verified number on the device for 30 days, and booking requires it.

**Traveller WhatsApp updates.** Opt-in only: an unticked box at the phone step ("Send my booking pass and updates on WhatsApp"), stored as `booking.whatsapp_updates_at`. Opted-in travellers get `booking_confirmed` on HOTEL_CONFIRMED and one `booking_update` on HOTEL_DECLINED, HOTEL_TIMEOUT, CANCELLED after payment, HOTEL_FAILED and REFUNDED (`TravellerNotifications`, through the same outbox). /privacy describes exactly this; change both together.

## Owner earnings and payouts

All new money columns are paise (`bigint`); the older rupee columns are unchanged and converted with `Ledger.paise`.

- **Ledger (`ledger_entry`, append-only; a trigger rejects UPDATE and DELETE).** Written inside the booking's transaction by `BookingService.move`:
  - the guest stayed or didn't turn up (first of CHECKED_IN, COMPLETED, NO_SHOW): `DEPOSIT_COLLECTED +deposit` and `COMMISSION −commission`, so the hotel is owed deposit − commission;
  - any refund after that, or HOTEL_FAILED: `REFUND −(deposit − commission)`, so nothing is owed;
  - declined, timed out or cancelled bookings never reach an owed state, so they write nothing.
  Each of those three types is written at most once per booking (unique index). Admins add `ADJUSTMENT ±` entries with a note. Balance owed = the sum of a hotel's entries.
- **Settlements.** Monday 10:00 Asia/Kolkata (`SettlementJob`), each hotel's entries from before Monday 00:00 that no settlement covers yet become one `settlement` for the previous Monday–Sunday, with its lines in `settlement_line`. Entries of a booking that is DISPUTED wait for a later week, and so does a total of zero or less. The admin pays by UPI and records the reference, which adds a `PAYOUT −amount` entry; each settlement exports as CSV (guest initials only).
- **Owner page (`/owner`).** "hisaab" on WhatsApp → a free-form reply with a link holding a random token (single use, 15 minutes; only its SHA-256 is stored, and the sent message's stored copy is redacted). Opening it creates a 30-day `owner_session` for that one hotel in the httpOnly `ls_owner` cookie (session id + HMAC, `Path=/api/owner`). Every `/api/owner/**` endpoint takes the hotel from the session only and accepts no hotel id. The page shows tonight's guests, rooms free tonight (editable, source `OWNER_PAGE`), this week's earnings and the next payout date, past payouts, and floor prices (amount only, never above the room's list price).

## Hotel photos

Admins upload several JPEG or PNG files at once (10 MB each). The backend checks size and magic bytes (HEIC is refused with instructions: prebuilt sharp can't decode HEVC), the imaging service applies the EXIF orientation, drops all metadata (EXIF, GPS, XMP, IPTC, ICC) and writes WebP at 400, 800 and 1600 px (never enlarged), and the backend stores them in S3-compatible storage (Cloudflare R2; S3Mock locally) as `hotels/<hotel>/<photo>-<width>.webp`, immutable. The original is never stored. The web app's `<Photo>` uses those files directly through a next/image loader. Order is `sort_order`; the cover is the first.

## Environments and jobs

Two environments, staging and production, each with its own database, WhatsApp number, Razorpay keys, LLM key and
Sentry environment ([DEPLOY.md](DEPLOY.md#two-environments)). `APP_ENV` says which one a backend is: production never
seeds data, staging never takes live Razorpay keys. Backups, restores, rollbacks and secret rotation:
[RUNBOOK.md](RUNBOOK.md).

Every `@Scheduled` job also has a ShedLock `@SchedulerLock` (JDBC provider, table `shedlock`, database time), so
two machines (a deploy's overlap, or more later) never run a job twice; a test runs two app instances on one
database to prove it, and another fails if a new job has no lock. Each run that gets its lock increments
`latestay.job.runs{job}`. Rate limits are still in memory, so production stays on one machine.

| Job | When (Asia/Kolkata) |
| --- | --- |
| Booking jobs (expiry, timeouts, arrival checks, completion, holds, refunds) | every 15 s |
| WhatsApp sender | every 5 s |
| Push sender (app notifications) | every 5 s |
| Analytics forwarder (to PostHog) | every 30 s |
| Availability ping | 21:00 |
| Morning digest | 09:00 |
| Review invites | 11:00 |
| Phone retention, owner-link cleanup, device-token cleanup | 03:30, 03:45, 03:50 |
| Weekly settlement | Monday 10:00 |

## Launch tracking

- **Events.** The 16 funnel events (`AnalyticsEventName`) go into `analytics_event` and, with `POSTHOG_API_KEY`,
  to PostHog's batch API **from the server** (`PostHogForwarder`): no analytics script in the browser, `$ip`
  blanked, GeoIP off, no person profiles. The web app posts its events to `POST /api/events` (browser events only);
  payment, confirmation, check-in, cancellation, turned-away and rescue are recorded by the backend itself
  (`BookingAnalytics`). Every property passes `AnalyticsSanitizer`: only the event's whitelisted keys, and only
  short lower-case tags (no run of 4+ digits, so no phone number or code), small numbers or booleans; prices travel
  as ₹500 bands ("1k-1.5k"). The distinct id is a random id from the browser's localStorage.
- **UTM.** The browser keeps the first visit's `utm_source`, `utm_medium` and `utm_campaign` and sends them (and its
  analytics id) with each search; they're stored on `stay_request` with the town of the nearest result, and copied to
  the booking. The server-rendered first search is tagged once afterwards (`POST /api/stay-requests/{id}/attribution`).
- **Promo codes.** `promo_code` / `promo_redemption`: a flat discount off the deposit (whole rupees), valid between
  two dates, a total number of uses, once per verified phone (hash). The traveller pays at least ₹1 online. A use
  is reserved at booking, used at payment, and given back if the booking expires unpaid or is refunded.
  `booking.deposit_amount` is what was paid online and `promo_discount_paise` the rest of the deposit; the ledger
  adds `PROMO_SUBSIDY +discount`, so the hotel is owed exactly what it would be without the code (LateStay's
  commission absorbs it, and can go below zero for a big code: the admin form warns).
- **LLM spend.** Each bot turn stores its calls, tokens, model and cost (`negotiation_turn.llm_cost_micro_usd`,
  priced with `LLM_PRICE_*`). `LLM_MONTHLY_BUDGET_USD` stops LLM calls for the rest of the month once reached; the
  chat answers from its templates.
- **/admin/funnel** (`Funnel`): different visitors per step over 7 or 30 days, by town and UTM source, with drop-off,
  and LLM spend per completed (checked-in or completed) booking. **/admin/today** and the 09:00 `morning_digest`
  (`DigestService`): last night's bookings, check-ins, completions, no-shows, rescues, open disputes, refunds,
  commission, total owed to hotels, hotels that didn't answer the ping and LLM cost.

## Pricing

The engine (`com.latestay.negotiation`, pure Java) builds every price from the session's `PricingPolicy`: list, floor,
slot, rounds, rounding, and a `PricingContext` (rooms free, total rooms, demand %), all stored on the session so it
keeps its prices. `PRICING_VERSION=1` (and every session opened before this) uses arrival time only.

- **Opening** (version 2): the arrival-time percentage of list (90/80/70/60), then the night: the last free room
  opens at 95% or more (never below a scarce hotel), a quarter of the rooms or fewer free +10 points, half or more
  free −10 points; then × demand %, capped at list (rounded down), and never below the floor.
- **Concessions:** 40/25/15/10% of the gap per round; halved when rooms are scarce. Always at least one rounding step
  lower, never below the floor, never up, always rounded (jqwik properties in `NegotiationPropertiesTest`).
- **Demand** (`com.latestay.pricing.PricingInputs`): `PRICING_WEEKEND_PERCENT` on Friday and Saturday nights × the
  town's `season_period` multiplier (admin: Quality > Season calendar; no overlaps per town), clamped to 70–150%.
  Floors never move; total rooms is `hotel.total_rooms`, rooms free is `rooms_available`.
- `sim/reports/pricing-compare.md`: the simulator's comparison of the two versions on the same scenarios
  (`make sim-compare`).

## Ratings and reliability

- **Reviews** (`com.latestay.review`): one per booking that ended CHECKED_IN or COMPLETED, from the leave time for 14
  days, from the pass (`/pass/[id]/review`): 1–5 stars, tags (Clean, Warm, Quiet, Friendly desk, As shown), up to 300
  characters with no phone numbers. Admins hide abusive text; the stars still count. Opted-in travellers get one
  `booking_update` at 11:00 after the stay (`ReviewInvites`). A hotel shows "New" until 3 reviews, then
  (5 × 4.0 + Σ stars) / (5 + n), one decimal. There are no other ratings.
- **Reliability** (`com.latestay.reliability`), last 30 days from `booking_event`: confirm rate (confirmed ÷
  confirmed + declined + timed out), median minutes from payment to Confirm, rescues (HOTEL_FAILED not settling a
  dispute) and upheld disputes (a disputed booking rescued, or "I did arrive" settled as stayed). Score = confirm
  rate − 10 per rescue or upheld dispute − 5 if the median is over 3 minutes; under 3 decisions a hotel starts at 80.
  On each decline, timeout, rescue or settled dispute the hotel is checked: under `RELIABILITY_MIN_CONFIRM_RATE`
  (after `RELIABILITY_MIN_DECISIONS`) or at `RELIABILITY_MAX_STRIKES` it is paused (`hotel.reliability_paused_at`):
  out of search and town pages, no new negotiations. The admin's "Reviewed, show again" unpauses it; only incidents
  after that can pause it again. /admin (Quality) and the owner page show the numbers.

## Groups

When no single room at a hotel fits the guests and it has two rooms free, search offers up to three pairs of room
types (the same type twice allowed). One negotiation covers both (`negotiation_room` lines): list and floor are the
sums of both rooms'. Agreeing takes both holds under the night's row lock, or neither; the booking has
`booking.rooms = 2` and `booking_room` lines, and `rooms_available` subtracts both. The owner gets
`booking_request_rooms` once approved (`WA_ROOMS_TEMPLATE`), else `booking_request`; the arrival check, cancellation,
owner page and /admin say "2 rooms".

## Share my stay

"Share with family" on a paid, confirmed or checked-in pass makes a random token (`booking_share`, one live per
booking; a new link turns off the old one). `/stay/[token]` shows the hotel's name, address and map, the arrival time
and the status, never the code, a phone number, the guest's name or a price. It ends 24 hours after the leave time
or when the traveller turns it off (410). Never cached by the service worker, `noindex`.

## Town pages

`/towns/[town]` for every town with an active, unpaused hotel, static and rebuilt hourly (`GET /api/towns`,
`/api/towns/{slug}`): an intro, "I need a room now" (searching from the town's centre), partner hotels with a typical
late-night price (25th–75th percentile of one-room late-night agreed prices paid in the last 90 days, rounded out to
₹50, only with 3 or more; never floors or live offers) and an FAQ worded from /refund-policy. Titles, descriptions,
an Open Graph image per town (`opengraph-image.tsx`, Plus Jakarta Sans from `src/assets/fonts`), `sitemap.xml` and
`robots.txt`; only `NEXT_PUBLIC_APP_ENV=production` is indexable.

## Mobile apps

- **Loading.** `mobile/` is a Capacitor 8 project. The apps load the live web app (`server.url`: production, or
  staging for staging builds; `allowNavigation` only our hosts and Razorpay's checkout), with `LateStayApp/<version>`
  added to the user agent. A web deploy updates the apps at once; only native changes need a store release. When the
  server can't be reached: on iOS the bundled `mobile/www/offline.html` shows "No connection", the saved passes and
  Retry; Android gives that page no Capacitor bridge, so the web app's service worker serves its own offline screen
  (`/~offline`, which in the app lists the saved passes) and the bundled page is the last resort (Retry only).
- **App-only code** lives in `web/src/lib/native` and `web/src/components/native`: detected with Capacitor's
  `isNativePlatform()` (and `html.native`, set before first paint by `NATIVE_BOOT_SCRIPT`), plugins imported lazily, so
  the website downloads none of it and looks exactly as before (`e2e/unchanged.spec.ts` compares pixels). CSS for the
  app only uses the `native:` variant. `/app/bookings` and `/app/help` 404 on the website (user agent check).
- **Shell:** bottom tabs (Tonight, Bookings, Help), safe areas, dark status bar text, Android back button (exits only
  from Tonight), haptics (Accept; hotel confirmed), links (maps, dialler, WhatsApp outside; policy pages in the in-app
  browser), native share sheet, location for "I need a room now", Razorpay's native SDK (`capacitor-razorpay`) for
  payments (UPI apps and the return to the app), saved passes in Preferences (`latestay.passes`).
- **Push** (`com.latestay.push`): after the deposit is paid the pass offers notifications (our card, then the
  system prompt; once per install). The app registers its FCM token with `POST /api/devices` for that booking (and the
  verified number's hash, so later bookings reach it). `PushNotifications` queues one `push_message` per booking and
  status (dedupe key) when a device is registered: confirmed, declined, timed out, cancelled after payment, rescued,
  refunded, and the 11:00 review request; `PushSender` sends through the Firebase Admin SDK
  (`FIREBASE_SERVICE_ACCOUNT_JSON`), deleting tokens FCM reports gone; tokens unseen for 90 days are deleted. Payloads
  carry the text and `bookingId` / `path` only: never a code, phone number or name. Tapping one opens the pass.
- **Deep links:** `/.well-known/apple-app-site-association` and `/.well-known/assetlinks.json` (web route handlers,
  from `APPLE_TEAM_ID`, `ANDROID_CERT_SHA256`, `MOBILE_APP_ID`) make /pass/* and /stay/* open in the app.
