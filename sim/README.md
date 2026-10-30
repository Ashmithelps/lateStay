# LateStay negotiation simulator

Simulated travellers haggle with the backend through its public API only: `POST /api/dev/negotiations` to open a session, then `POST /api/negotiations/{id}/messages` for each message. The backend's engine sets every price; the simulator checks that no offer goes below the floor and that every reply mentions only the engine's price.

## Run

1. Start the database and the backend with the local profile (see the root README):

   ```sh
   docker compose up -d --wait
   cd backend && set -a && source .env && set +a && SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
   ```

2. From the repo root:

   ```sh
   make sim-offline          # scripted personas, no API key, free
   make sim                  # LLM personas (needs ANTHROPIC_API_KEY in sim/.env)
   make sim N=50 ARGS="--persona-model claude-haiku-5-5 --concurrency 8"
   make sim-test             # unit tests + lint
   make sim-compare N=400    # old vs new pricing on the same scenarios -> reports/pricing-compare.md
   ```

   Or directly: `cd sim && uv run latestay-sim --help`. uv installs Python 3.12 and the dependencies on first run.

Copy `sim/.env.example` to `sim/.env` for the API key and defaults.

## What it does

- **Personas** (10, assigned round-robin): polite student, hard haggler, family asking about heaters, Hinglish speaker, Punjabi speaker (Latin script), accepts fast, asks many questions, lowballer opening at Rs 100, prompt-injection attacker, topic changer. Each has a hidden budget drawn as a % of list price. The persona model gets its own system prompt with its style and budget; it never sees the floor. With `--offline` (or when the persona model fails or refuses) a deterministic script plays the persona.
- **Scenarios**: list ₹1,000-3,000 (steps of 250), floor 40/50/60/70% of list, `LATE_NIGHT` arriving 21:30, 23:00 or 00:45 and `EARLY_MORNING` arriving 05:30 (the engine rejects arrivals outside a slot's window).
- **Conversations**: `--n` (default 200), up to 8 traveller turns, stopping on AGREED, NO_DEAL, or the traveller walking away.

## Output

`sim/reports/<timestamp>/` (git-ignored):

- `report.md`: summary, per-persona table, chart and 5 sample transcripts (best, worst, an injection attempt, Hinglish/Punjabi, most questions)
- `results.csv`: one row per conversation
- `agreed_vs_budget.png`: agreed price against the traveller's hidden budget

The command exits with status 1 if there is any floor violation or reply-number mismatch, so it can gate CI.

## Cost

With LLM personas, each traveller turn is one persona call: a default run is about 1,600 calls. Roughly $10-15 on `claude-opus-5-5` and under $0.50 on `claude-haiku-5-5`; the report shows the measured figure. Backend LLM cost is estimated from the token counts the backend reports, using `SIM_BACKEND_MODEL` prices from `src/latestay_sim/pricing.py`.

## Pricing compare

`--compare` runs each scenario twice, once under the engine's old rules (`pricingVersion` 1) and once under occupancy pricing (2), with the same persona, hidden budget and random seed, on a night with 1, 2, 4 or 8 of 10 rooms free and a weekday, weekend, peak or off-season demand. It writes `reports/pricing-compare.md` (kept in git) and `pricing-compare.csv`: deal rate, average agreed price and revenue per available room, overall and by occupancy, night and persona. With scripted personas the run is deterministic. The dev endpoint accepts `pricingVersion`, `roomsFree`, `totalRooms` and `demandPercent` for this.
