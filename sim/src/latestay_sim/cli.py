"""Command line entry point: latestay-sim."""

from __future__ import annotations

import argparse
import os
import random
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from latestay_sim.backend import BackendClient, BackendError
from latestay_sim.metrics import by_persona, summarize
from latestay_sim.personas import PERSONAS, budget_for
from latestay_sim.report import RunInfo, write_report
from latestay_sim.runner import MAX_TURNS, ConversationResult, run_conversation
from latestay_sim.scenarios import sample
from latestay_sim.traveller import LlmTraveller, ScriptedTraveller

SIM_DIR = Path(__file__).resolve().parents[2]
DEFAULT_PERSONA_MODEL = "claude-opus-5-5"


def load_env_file(path: Path) -> None:
    """Minimal .env loader: KEY=VALUE lines; real environment variables win."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="latestay-sim", description="Simulate travellers haggling with LateStay.")
    parser.add_argument("--n", type=int, default=200, help="number of conversations (default 200)")
    parser.add_argument("--seed", type=int, default=7, help="random seed for scenarios and budgets")
    parser.add_argument("--concurrency", type=int, default=4, help="conversations in parallel (default 4)")
    parser.add_argument("--max-turns", type=int, default=MAX_TURNS, help="traveller turns per conversation")
    parser.add_argument("--offline", action="store_true", help="scripted personas, no persona LLM or API key")
    parser.add_argument("--persona-model", default=None,
                        help=f"persona model (default $SIM_PERSONA_MODEL or {DEFAULT_PERSONA_MODEL})")
    parser.add_argument("--backend-model", default=None,
                        help="model the backend uses, for cost estimates "
                             "(default $SIM_BACKEND_MODEL or claude-haiku-5-5)")
    parser.add_argument("--base-url", default=None, help="backend URL (default $SIM_BASE_URL or http://localhost:8080)")
    parser.add_argument("--out", type=Path, default=SIM_DIR / "reports", help="reports directory")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    load_env_file(SIM_DIR / ".env")
    args = parse_args(argv)
    base_url = args.base_url or os.environ.get("SIM_BASE_URL") or "http://localhost:8080"
    persona_model = None if args.offline else (
        args.persona_model or os.environ.get("SIM_PERSONA_MODEL") or DEFAULT_PERSONA_MODEL)
    backend_model = args.backend_model or os.environ.get("SIM_BACKEND_MODEL") or "claude-haiku-5-5"

    llm_client = None
    if persona_model:
        if not os.environ.get("ANTHROPIC_API_KEY"):
            print("ANTHROPIC_API_KEY is not set. Put it in sim/.env, or run with --offline.", file=sys.stderr)
            return 2
        import anthropic

        llm_client = anthropic.Anthropic(timeout=60.0)

    backend = BackendClient(base_url)
    try:
        health = backend.check_health()
    except BackendError as e:
        print(f"{e}\nStart it with: cd backend && SPRING_PROFILES_ACTIVE=local ./gradlew bootRun", file=sys.stderr)
        return 2

    rng = random.Random(args.seed)
    scenarios = sample(args.n, rng)
    jobs = []
    for i, scenario in enumerate(scenarios):
        persona = PERSONAS[i % len(PERSONAS)]
        budget = budget_for(persona, scenario, rng)
        scripted = ScriptedTraveller(persona, budget, random.Random(rng.random()))
        traveller = LlmTraveller(llm_client, persona_model, persona, budget, scenario, scripted) \
            if llm_client else scripted
        jobs.append((traveller, persona, scenario, budget))

    print(f"Running {args.n} conversations against {base_url} "
          f"({'offline personas' if not persona_model else persona_model}, concurrency {args.concurrency})",
          file=sys.stderr)
    results: list[ConversationResult] = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(run_conversation, backend, t, p, s, b, args.max_turns) for t, p, s, b in jobs]
        for done, future in enumerate(as_completed(futures), 1):
            results.append(future.result())
            if done % 20 == 0 or done == len(futures):
                print(f"  {done}/{len(futures)}", file=sys.stderr)
    backend.close()
    results.sort(key=lambda r: r.number)

    overall = summarize(results, persona_model, backend_model)
    personas = by_persona(results, persona_model, backend_model)
    timestamp = datetime.now().strftime("%Y-%m-%dT%H-%M-%S")
    info = RunInfo(timestamp, args.n, args.seed, persona_model, backend_model, args.concurrency, base_url,
                   health.get("postgis"))
    report = write_report(results, overall, personas, info, args.out / timestamp)

    print(f"\nDeals {overall.deals}/{overall.conversations} ({overall.deal_rate:.1f}%), "
          f"floor violations {overall.floor_violations}, number mismatches {overall.mismatches}, "
          f"template replies {overall.template_replies}/{overall.replies}, errors {overall.errors}")
    print(f"Report: {report}")
    if overall.floor_violations or overall.mismatches:
        print("FAIL: floor violations or number mismatches found", file=sys.stderr)
        return 1
    if overall.errors == overall.conversations:
        return 2
    return 0
