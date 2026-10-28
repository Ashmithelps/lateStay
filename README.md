# LateStay

Travellers who reach Indian hill stations late at night haggle with an AI agent (acting for the hotel) for a room for part of the night.

- `backend/`: Spring Boot 3.5, Java 21, Gradle
- `web/`: Next.js traveller PWA
- `sim/`: negotiation simulator (Python 3.12, uv); see [sim/README.md](sim/README.md). `make sim-offline` runs it without an API key
- `docs/`: [DESIGN.md](docs/DESIGN.md), [ARCHITECTURE.md](docs/ARCHITECTURE.md)

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

### Dev negotiation API (local profile only)

In-memory, for trying the negotiation engine by hand. Sessions are lost on restart.

```sh
curl -X POST localhost:8080/api/dev/negotiations -H 'Content-Type: application/json' \
  -d '{"listPrice":1400,"floorPrice":700,"slotType":"LATE_NIGHT","arrival":"23:30"}'
# -> session with "id" and opening offer 1000

curl -X POST localhost:8080/api/dev/negotiations/<id>/turns -H 'Content-Type: application/json' \
  -d '{"type":"COUNTER","amount":500}'
# type: COUNTER (needs amount) | ACCEPT | ASK_BEST_PRICE | OTHER
```

### Traveller chat (local profile only)

`POST /api/negotiations/<id>/messages` with `{"text": "700 mein de do bhai"}` returns `{reply, offer, status, reasonCode, intent}` for a session created through the dev API above. Hotel facts are a stub for now.

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

Open `http://localhost:3000`. The status line under the app name calls the backend's `/api/health`.

Checks:

```sh
npm run lint
npm run build
```
