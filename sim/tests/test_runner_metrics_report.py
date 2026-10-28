import csv
import random

from conftest import FakeBackend

from latestay_sim.metrics import by_persona, percentile, summarize
from latestay_sim.personas import PERSONAS, PERSONAS_BY_KEY
from latestay_sim.report import RunInfo, pick_samples, write_report
from latestay_sim.runner import run_conversation
from latestay_sim.scenarios import sample
from latestay_sim.traveller import ScriptedTraveller


def run(backend, persona_key, scenario, budget):
    persona = PERSONAS_BY_KEY[persona_key]
    return run_conversation(backend, ScriptedTraveller(persona, budget, random.Random(2)), persona, scenario, budget)


def test_stops_on_agreed_and_records_price(scenario):
    result = run(FakeBackend(), "fast_accepter", scenario, budget=1700)

    assert result.outcome == "AGREED"
    assert result.agreed_price == 1600
    assert len(result.turns) == 1
    assert result.floor_violations == 0 and result.mismatches == 0


def test_low_budget_ends_without_deal(scenario):
    result = run(FakeBackend(), "lowballer", scenario, budget=800)

    assert result.outcome in ("NO_DEAL", "WALKED_AWAY", "MAX_TURNS")
    assert result.agreed_price is None
    assert len(result.turns) <= 8


def test_detects_number_mismatch_and_floor_violation(scenario):
    leaky = FakeBackend(reply_template="Only ₹{offer:,}, our floor is ₹1,000.", ignore_floor=True)
    result = run(leaky, "hard_haggler", scenario, budget=900)

    assert result.mismatches == len(result.turns) > 0
    assert result.floor_violations > 0


def test_percentile_nearest_rank():
    values = [float(v) for v in range(1, 101)]
    assert percentile(values, 50) == 50
    assert percentile(values, 95) == 95
    assert percentile([], 50) is None


def test_summary_and_report_files(tmp_path):
    backend = FakeBackend()
    rng = random.Random(4)
    results = []
    for i, s in enumerate(sample(30, rng)):
        persona = PERSONAS[i % len(PERSONAS)]
        budget = int(s.list_price * rng.uniform(0.4, 1.0))
        results.append(run_conversation(backend, ScriptedTraveller(persona, budget, random.Random(i)),
                                        persona, s, budget))

    overall = summarize(results, None, "claude-haiku-5-5")
    personas = by_persona(results, None, "claude-haiku-5-5")
    assert overall.conversations == 30
    assert overall.floor_violations == 0 and overall.mismatches == 0
    assert overall.template_replies == overall.replies
    assert set(personas) == {p.key for p in PERSONAS}
    if overall.deals:
        assert 0 <= overall.agreed_pct_above_floor and overall.agreed_pct_of_list <= 100

    info = RunInfo("2026-10-28T20-00-00", 30, 4, None, "claude-haiku-5-5", 1, "http://fake", "3.5")
    report = write_report(results, overall, personas, info, tmp_path / "run")

    text = report.read_text()
    assert "## Summary" in text and "## By persona" in text and "## Sample transcripts" in text
    assert text.count("\n### ") == len(pick_samples(results)) >= 3
    assert "Prompt-injection attempt" in text
    rows = list(csv.DictReader((tmp_path / "run" / "results.csv").open()))
    assert len(rows) == 30
    assert (tmp_path / "run" / "agreed_vs_budget.png").stat().st_size > 10_000
