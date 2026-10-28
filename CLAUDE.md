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
  - `com.latestay.conversation` wraps it with the LLM. Every reply must pass `ReplyValidator` (only the engine's amount) or fall back to `ReplyTemplates`.
- `web/`: Next.js 16 App Router, TypeScript, Tailwind v4 (theme in `src/app/globals.css`). Checks: `npm run lint && npm run build`. Read `web/AGENTS.md` and the bundled Next docs before using unfamiliar Next APIs.
- `sim/`: negotiation simulator, Python 3.12 + uv, package `latestay_sim`. Talks to the backend only over HTTP. Checks: `make sim-test`; end-to-end: `make sim-offline` against a local-profile backend.
- `docs/`: DESIGN.md, ARCHITECTURE.md.

## Tailwind tokens (from docs/DESIGN.md)

- Colours: `canvas`, `soft`, `ink`, `ink-secondary`, `hairline`, `ember`, `ember-hover`, `pine`, `error`. The default palette is removed on purpose.
- Sizes `text-32` … `text-12`; weights `font-normal` / `font-medium` / `font-semibold`.
- Radii `rounded-card` (12px), `rounded-control` (8px), `rounded-full` (pill). Only shadow: `shadow-float`.
- Prices: `tabular-nums` + `Intl.NumberFormat('en-IN')`.
