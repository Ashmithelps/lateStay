import random

from latestay_sim import compare
from latestay_sim.personas import PERSONAS
from latestay_sim.runner import ConversationResult
from latestay_sim.scenarios import Pricing, Scenario


def result(price: int | None, list_price: int = 2000) -> ConversationResult:
    scenario = Scenario(list_price, 50, "LATE_NIGHT", "23:00", Pricing(2, 2, 10, 100))
    r = ConversationResult(1, PERSONAS[0], scenario, 1500, session_id="s", opening_offer=1600)
    r.outcome = "AGREED" if price else "NO_DEAL"
    r.agreed_price = price
    return r


def test_revpar_counts_every_conversation_as_a_room_on_offer():
    s = compare.summarize([result(1200), result(1000), result(None), result(None)])
    assert s.deals == 2
    assert s.deal_rate == 50.0
    assert s.avg_agreed == 1100
    assert s.revpar == 550


def test_both_arms_share_the_scenario_persona_budget_and_seed():
    jobs = compare.jobs(20, random.Random(3))
    again = compare.jobs(20, random.Random(3))
    assert [(j.scenario, j.budget, j.seed) for j in jobs] == [(j.scenario, j.budget, j.seed) for j in again]
    for job in jobs:
        old, new = compare.arm(job, 1), compare.arm(job, 2)
        assert old.pricing.version == 1 and new.pricing.version == 2
        assert (old.list_price, old.floor_price, old.arrival) == (new.list_price, new.floor_price, new.arrival)
        assert old.pricing.rooms_free == new.pricing.rooms_free
        assert job.occupancy in {label for _, label in compare.OCCUPANCY}


def test_report_has_the_three_numbers(tmp_path):
    job = compare.jobs(1, random.Random(1))[0]
    pairs = [compare.Pair(job, result(1200), result(1300))]
    path = compare.write_report(pairs, tmp_path / "pricing-compare.md", n=1, seed=1, persona_model=None,
                                base_url="http://x", timestamp="t")
    text = path.read_text()
    assert "Deal rate" in text and "Average agreed price" in text and "RevPAR" in text
    assert "+₹100" in text
    assert path.with_suffix(".csv").exists()
