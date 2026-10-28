# Architecture

LateStay matches travellers who reach Indian hill stations late at night with hotels that still have rooms, and lets them haggle for part of the night with an AI agent acting for the hotel.

## Components

- **Traveller PWA (`web/`).** Next.js App Router, mobile-first. Search, hotel pages, the haggle chat and the booking pass. Talks only to the backend's `/api/**`.
- **Backend (`backend/`).** Spring Boot 3, Java 21. Modules:
  - **Matching:** finds hotels near the traveller with rooms free tonight (PostGIS distance queries).
  - **Negotiation engine:** deterministic pricing rules per hotel (floor, opening price, concession steps, time-of-night). The LLM only phrases messages and interprets traveller intent; it never decides a price. Every number it says comes from the engine.
  - **Bookings:** holds, confirmations, the 4-digit booking pass code, check-in windows.
  - **Payments:** collects payment and settles to hotels.
- **Hotel owners (WhatsApp only).** Owners have no app or dashboard. They update availability and pricing rules, and get booking alerts, through WhatsApp messages handled by the backend.
- **Database.** Postgres 16 with PostGIS, migrated with Flyway.
- **Simulator (`sim/`).** Arrives in M3.

```
Traveller (PWA) ──HTTPS──> Spring Boot backend <──WhatsApp──> Hotel owners
                                 │
                         Postgres + PostGIS
```
