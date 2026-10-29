# CLAUDE.md

## Rules

- Never add "Co-Authored-By", "Generated with Claude Code" or any Claude/Anthropic attribution to commits, PR titles, PR bodies or code comments. Do not commit unless I ask.
- The LLM never decides a price. Prices come only from the negotiation engine's deterministic code.
- All frontend work follows docs/DESIGN.md, including its banned list.
- Secrets live in git-ignored .env files; keep .env.example files up to date.
- Work in small tested increments; run the tests before saying a task is done.

## Layout

- `backend/`: Spring Boot 3.5, Java 21, Gradle (Kotlin DSL), base package `com.latestay`. DTOs are Java records. Migrations in `src/main/resources/db/migration`. Tests: `./gradlew test` (Testcontainers, needs Docker); live LLM tests are tagged `llm` and run only via `./gradlew llmTest`.
  - `com.latestay.negotiation` is pure Java (ArchUnit-enforced): no Spring, DB or LLM.
  - Persistence wraps the engine: `com.latestay.session` stores `SessionSnapshot`s and rebuilds sessions with `NegotiationEngine.restore`. SQL is plain `JdbcClient` (no JPA entities); `com.latestay.hotel`, `com.latestay.stay` (search, `StayWindow`, night_of), `com.latestay.admin` (HTTP basic). Local seed data: `seed/local-hotels.json`, loaded by `LocalSeedData` in the local profile only.
  - Integration tests share one PostGIS container: each test class creates its own hotels in its own map area and never assumes empty tables.
  - `com.latestay.booking`: bookings from AGREED sessions. Status changes only through `BookingService.move`, guarded by `BookingTransitions` (the state table); timeouts in `BookingJobs` (injected `Clock`; `latestay.jobs.enabled=false` in tests, which call the jobs directly). Money comes from the session's agreed price, never the client. `com.latestay.payment`: Razorpay gateway (REST, no SDK), signatures; `FakeRazorpayGateway` only when `latestay.razorpay.allow-fake` (local profile, tests). Every webhook handler must stay idempotent.
  - `com.latestay.whatsapp`: the owner channel (Cloud API). Inbound is stored first by `wa_message_id` (idempotent), then parsed by `OwnerReplyParser` (regex only); outbound is queued in `whatsapp_message` and sent by `WhatsAppSender`. Business-initiated messages must be one of the approved templates in docs/whatsapp-templates.md; keep that file and `WhatsAppMessages` in sync. Booking notifications listen for `BookingStatusChanged`.
  - `com.latestay.web`: rate limits (Bucket4j, in memory, so one instance), `Pii.mask` / `Pii.scrub` (never log a full phone number; production JSON logs scrub every field too), Sentry scrubbing and the `prod` startup checks. New env vars also go in the tables in docs/DEPLOY.md.
  - `com.latestay.conversation` wraps it with the LLM. Every reply must pass `ReplyValidator` (only the engine's amount) or fall back to `ReplyTemplates`.
- `web/`: Next.js 16 App Router, TypeScript, Tailwind v4 (theme in `src/app/globals.css`). No `cacheComponents`: each page is an async server component that fetches its first data (`lib/server-fetch.ts`) and passes it to its client screen as TanStack `initialData`, so the LCP photo is in the first HTML; keep LCP content out of Suspense boundaries. Service worker: Serwist (`src/app/sw.ts`, built by `src/app/serwist/[path]/route.ts`); never cache admin, dev or webhook traffic. Sentry is errors-only and scrubs phones (`lib/sentry-scrub.ts`). Checks: `npm run lint && npm run build`. Read `web/AGENTS.md` and the bundled Next docs before using unfamiliar Next APIs.
- `sim/`: negotiation simulator, Python 3.12 + uv, package `latestay_sim`. Talks to the backend only over HTTP. Checks: `make sim-test`; end-to-end: `make sim-offline` against a local-profile backend.
- `docs/`: DESIGN.md, ARCHITECTURE.md.

## Tailwind tokens (from docs/DESIGN.md)

- Colours: `canvas`, `soft`, `ink`, `ink-secondary`, `hairline`, `ember`, `ember-button`, `ember-button-hover`, `pine`, `error`. The default palette is removed on purpose. Primary buttons use `ember-button` (white on `#D9572B` fails AA); only `Button variant="primary"` and the search pill's round button use it.
- Web components live in `web/src/components/` (Tailwind only). `/admin` (in `src/app/admin/`) uses the same tokens with denser controls and no ember; its API types are hand-written in `src/lib/admin-api.ts` because the admin API is kept out of the public OpenAPI spec. API types come from `web/openapi.json` (written by the backend's `OpenApiSpecIT`) via `npm run gen:api`. Checks: `npm test`, `npm run lint`, `npm run build`; `npm run screenshots` (Playwright, needs the local backend) writes `web/screenshots/`.
- Sizes `text-32` … `text-12` (plus `text-code`, 56 px, for the booking pass's desk code only); weights `font-normal` / `font-medium` / `font-semibold`.
- Radii `rounded-card` (12px), `rounded-control` (8px), `rounded-full` (pill). Only shadow: `shadow-float`.
- Prices: `tabular-nums` + `Intl.NumberFormat('en-IN')`.
