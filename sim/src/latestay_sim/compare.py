"""Old pricing (version 1) against occupancy pricing (version 2) on the same scenarios.

Each scenario runs twice with the same persona, hidden budget and random seed, so with scripted personas the only
difference between the two conversations is the engine's policy. Every conversation stands for one room the hotel
could sell that night, so revenue per available room (RevPAR) is total agreed revenue / conversations.
"""

from __future__ import annotations

import csv
import random
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from pathlib import Path
from statistics import mean

from latestay_sim.backend import BackendClient
from latestay_sim.personas import PERSONAS, Persona, budget_for
from latestay_sim.runner import ConversationResult, run_conversation
from latestay_sim.scenarios import Pricing, Scenario, sample
from latestay_sim.traveller import LlmTraveller, ScriptedTraveller

TOTAL_ROOMS = 10
# Rooms free out of 10: the last room, scarce (a quarter or fewer), in between, half empty or more.
OCCUPANCY = ((1, "last room"), (2, "scarce"), (4, "in between"), (8, "half empty"))
NIGHTS = (("weekday", 100), ("weekend", 110), ("peak season", 130), ("off season", 85))


@dataclass(frozen=True)
class Job:
    number: int
    persona: Persona
    scenario: Scenario  # without pricing; each arm adds its own
    budget: int
    seed: float
    occupancy: str
    night: str


def jobs(n: int, rng: random.Random) -> list[Job]:
    out = []
    for i, base in enumerate(sample(n, rng)):
        persona = PERSONAS[i % len(PERSONAS)]
        rooms_free, occupancy = OCCUPANCY[rng.randrange(len(OCCUPANCY))]
        night, demand = NIGHTS[rng.randrange(len(NIGHTS))]
        scenario = replace(base, pricing=Pricing(2, rooms_free, TOTAL_ROOMS, demand))
        out.append(Job(i + 1, persona, scenario, budget_for(persona, base, rng), rng.random(), occupancy, night))
    return out


def arm(job: Job, version: int) -> Scenario:
    assert job.scenario.pricing is not None
    return replace(job.scenario, pricing=replace(job.scenario.pricing, version=version))


@dataclass
class Pair:
    job: Job
    old: ConversationResult
    new: ConversationResult


def run(backend: BackendClient, all_jobs: list[Job], max_turns: int, concurrency: int, llm_client=None,
        persona_model: str | None = None) -> list[Pair]:
    def one(job: Job, version: int) -> ConversationResult:
        scenario = arm(job, version)
        scripted = ScriptedTraveller(job.persona, job.budget, random.Random(job.seed))
        traveller = LlmTraveller(llm_client, persona_model, job.persona, job.budget, scenario, scripted) \
            if llm_client else scripted
        return run_conversation(backend, traveller, job.persona, scenario, job.budget, max_turns)

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        old = [pool.submit(one, j, 1) for j in all_jobs]
        new = [pool.submit(one, j, 2) for j in all_jobs]
        return [Pair(j, o.result(), n.result()) for j, o, n in zip(all_jobs, old, new, strict=True)]


@dataclass(frozen=True)
class ArmSummary:
    conversations: int
    deals: int
    deal_rate: float
    avg_agreed: float | None
    avg_pct_of_list: float | None
    revpar: float
    floor_violations: int
    mismatches: int
    errors: int


def summarize(results: list[ConversationResult]) -> ArmSummary:
    deals = [r for r in results if r.agreed]
    return ArmSummary(
        conversations=len(results),
        deals=len(deals),
        deal_rate=len(deals) / len(results) * 100 if results else 0.0,
        avg_agreed=mean(r.agreed_price for r in deals) if deals else None,
        avg_pct_of_list=mean(r.agreed_price / r.scenario.list_price * 100 for r in deals) if deals else None,
        revpar=sum(r.agreed_price for r in deals) / len(results) if results else 0.0,
        floor_violations=sum(r.floor_violations for r in results),
        mismatches=sum(r.mismatches for r in results),
        errors=sum(r.outcome == "ERROR" for r in results),
    )


def _rupees(value: float | None) -> str:
    return "–" if value is None else f"₹{value:,.0f}"


def _pct(value: float | None) -> str:
    return "–" if value is None else f"{value:.1f}%"


def _delta(old: float | None, new: float | None, money: bool) -> str:
    if old is None or new is None:
        return "–"
    d = new - old
    sign = "+" if d > 0 else "−" if d < 0 else "±"
    return f"{sign}₹{abs(d):,.0f}" if money else f"{sign}{abs(d):.1f} pts"


def _table(rows: list[tuple[str, list[Pair]]]) -> list[str]:
    lines = [
        "| | Conversations | Deal rate (old → new) | Avg agreed price (old → new) | RevPAR (old → new) "
        "| RevPAR change |",
        "| --- | ---: | --- | --- | --- | ---: |",
    ]
    for label, pairs in rows:
        if not pairs:
            continue
        o = summarize([p.old for p in pairs])
        n = summarize([p.new for p in pairs])
        lines.append(
            f"| {label} | {o.conversations} | {_pct(o.deal_rate)} → {_pct(n.deal_rate)} "
            f"| {_rupees(o.avg_agreed)} → {_rupees(n.avg_agreed)} | {_rupees(o.revpar)} → {_rupees(n.revpar)} "
            f"| {_delta(o.revpar, n.revpar, True)} |")
    return lines


def write_report(pairs: list[Pair], path: Path, *, n: int, seed: int, persona_model: str | None, base_url: str,
                 timestamp: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    old = summarize([p.old for p in pairs])
    new = summarize([p.new for p in pairs])
    personas = "scripted personas (deterministic, no LLM)" if persona_model is None else persona_model
    lines = [
        "# Pricing compare: old policy vs occupancy pricing",
        "",
        f"{timestamp} · {n} scenarios, each run under both policies · seed {seed} · {personas} · {base_url}",
        "",
        "- **Old (version 1):** the engine before this change. Opening offer by arrival time only (90/80/70/60% of "
        "list), concessions of 40/25/15/10% of the gap.",
        "- **New (version 2):** the same, then the night: the last free room opens at 95% of list or more, a hotel "
        "with a quarter of its rooms or fewer free opens 10 points higher and concedes half as fast, a half-empty "
        "one opens 10 points lower; the opening is scaled by the night's demand (weekend 110%, a season's "
        "multiplier) and capped at list. Floors never move.",
        "- **Same scenarios:** list ₹1,000–3,000, floors 40–70% of list, the four arrival times, 10 rooms in all "
        "with 1, 2, 4 or 8 free, on a weekday (100%), weekend (110%), peak-season (130%) or off-season (85%) "
        "night. Each scenario keeps its persona, hidden budget and random seed in both runs.",
        "- **RevPAR** here is agreed revenue ÷ conversations: each conversation stands for one room the hotel "
        "could sell that night.",
        "",
        "## Overall",
        "",
        "| | Old | New | Change |",
        "| --- | ---: | ---: | ---: |",
        f"| Deal rate | {_pct(old.deal_rate)} | {_pct(new.deal_rate)} "
        f"| {_delta(old.deal_rate, new.deal_rate, False)} |",
        f"| Average agreed price | {_rupees(old.avg_agreed)} | {_rupees(new.avg_agreed)} "
        f"| {_delta(old.avg_agreed, new.avg_agreed, True)} |",
        f"| Agreed, % of list | {_pct(old.avg_pct_of_list)} | {_pct(new.avg_pct_of_list)} "
        f"| {_delta(old.avg_pct_of_list, new.avg_pct_of_list, False)} |",
        f"| RevPAR | {_rupees(old.revpar)} | {_rupees(new.revpar)} | {_delta(old.revpar, new.revpar, True)} |",
        f"| Deals | {old.deals} | {new.deals} | |",
        f"| Floor violations | {old.floor_violations} | {new.floor_violations} | |",
        f"| Replies naming another price than the engine's | {old.mismatches} | {new.mismatches} | |",
        f"| Errors | {old.errors} | {new.errors} | |",
        "",
        "## By rooms free (out of 10)",
        "",
        *_table([(f"{free} free ({label})", [p for p in pairs if p.job.occupancy == label])
                 for free, label in OCCUPANCY]),
        "",
        "## By night",
        "",
        *_table([(f"{night} ({demand}%)", [p for p in pairs if p.job.night == night]) for night, demand in NIGHTS]),
        "",
        "## By persona",
        "",
        *_table([(persona.key.replace("_", " "), [p for p in pairs if p.job.persona.key == persona.key])
                 for persona in PERSONAS]),
        "",
        "## What this does and doesn't show",
        "",
        "- The personas' budgets are a share of list price and don't react to weekends, seasons or how full the "
        "hotel is. Real travellers pay more on a busy night, so this measures how the policy behaves against a "
        "fixed willingness to pay, not real demand.",
        "- A room not sold in one conversation could still sell to the next traveller on a busy night; the RevPAR "
        "here counts it as unsold.",
        "- Both runs went through the backend's public API. The simulator checks every offer against the floor "
        "and every reply's number against the engine's.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")
    _write_csv(pairs, path.with_suffix(".csv"))
    return path


def _write_csv(pairs: list[Pair], path: Path) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["scenario", "persona", "list_price", "floor_price", "arrival", "rooms_free", "night", "budget",
                    "old_opening", "old_outcome", "old_agreed", "new_opening", "new_outcome", "new_agreed"])
        for p in pairs:
            s = p.job.scenario
            w.writerow([p.job.number, p.job.persona.key, s.list_price, s.floor_price, s.arrival,
                        s.pricing.rooms_free if s.pricing else "", p.job.night, p.job.budget, p.old.opening_offer,
                        p.old.outcome, p.old.agreed_price or "", p.new.opening_offer, p.new.outcome,
                        p.new.agreed_price or ""])
