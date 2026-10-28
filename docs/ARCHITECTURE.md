# Architecture

LateStay matches travellers who reach Indian hill stations late at night with hotels that still have rooms, and lets them haggle for part of the night with an AI agent acting for the hotel.

## Components

- **Traveller PWA (`web/`).** Next.js App Router, mobile-first. Search, hotel pages, the haggle chat and the booking pass. Talks only to the backend's `/api/**`.
- **Backend (`backend/`).** Spring Boot 3, Java 21. Modules:
  - **Matching:** finds hotels near the traveller with rooms free tonight (PostGIS distance queries).
  - **Negotiation engine:** deterministic pricing rules per hotel (floor, opening price, concession steps, time-of-night). The LLM only phrases messages and interprets traveller intent; it never decides a price. Every number it says comes from the engine.
  - **Bookings:** holds, confirmations, the 4-digit booking pass code, check-in windows.
  - **Payments:** collects payment and settles to hotels.
- **Hotel owners (WhatsApp only).** Owners have no app or dashboard. Through the WhatsApp Business Cloud API they answer a 21:00 availability ping (a hotel that hasn't answered for the night is hidden from search) and confirm or decline paid bookings with template buttons; the 5-minute confirmation timeout refunds the traveller if they don't. Replies are parsed with fixed rules (button payloads, then "rooms 2" / "2 room khali" style text), never an LLM. Webhooks are verified with X-Hub-Signature-256 and deduplicated by WhatsApp message id; outbound messages go through an outbox table (`whatsapp_message`), so no Graph API call runs inside a booking transaction. Templates: docs/whatsapp-templates.md.
- **Database.** Postgres 16 with PostGIS, migrated with Flyway. `hotel` (geography point, amenities, policies), `hotel_photo`, `room_type`, `floor_rule` (per slot, weekday mask, season, min hours), `nightly_availability` (per hotel and night; an arrival up to 09:00 belongs to the previous night), `stay_request` (each search), `booking` / `booking_event` / `payment` (deposits through Razorpay), `whatsapp_message` (every owner message in and out), `negotiation_session` (state plus a snapshot of the pricing policy it opened with) and `negotiation_turn` (every message, with intent, decision and validator result). Search uses `ST_DWithin` / `ST_Distance` within 10 km; "from" prices are the engine's opening offers.
- **Persistence boundary.** The engine stays pure Java. Sessions are saved as snapshots and rebuilt with `NegotiationEngine.restore`; the database layer (`com.latestay.session`) wraps it, and each message runs in one transaction holding a row lock on its session.
- **Simulator (`sim/`).** Simulated travellers (LLM or scripted personas) haggle through the public API and report deal rates, floor violations and reply-number mismatches.

```
Traveller (PWA) ──HTTPS──> Spring Boot backend <──WhatsApp──> Hotel owners
                                 │
                         Postgres + PostGIS
```
