import random
import re

from latestay_sim.personas import PERSONAS, budget_for, system_prompt
from latestay_sim.scenarios import FLOOR_PERCENTS, grid, sample


def test_grid_only_pairs_arrivals_with_their_slot():
    for s in grid():
        if s.slot_type == "EARLY_MORNING":
            assert s.arrival == "05:30"
        else:
            assert s.arrival in ("21:30", "23:00", "00:45")
        assert 1000 <= s.list_price <= 3000
        assert s.floor_percent in FLOOR_PERCENTS
        assert 40 * s.list_price <= 100 * s.floor_price <= 70 * s.list_price


def test_sample_is_seeded():
    assert sample(20, random.Random(1)) == sample(20, random.Random(1))
    assert len(sample(200, random.Random(1))) == 200


def test_ten_personas_with_budgets_in_range():
    assert len(PERSONAS) == 10
    rng = random.Random(3)
    for persona in PERSONAS:
        for s in grid():
            low, high = persona.budget_percent
            budget = budget_for(persona, s, rng)
            assert low / 100 * s.list_price - 10 <= budget <= high / 100 * s.list_price + 10


def test_persona_prompt_never_contains_the_floor():
    for persona in PERSONAS:
        for s in grid():
            budget = s.floor_price + 130  # never equal to the floor
            prompt = system_prompt(persona, budget, s)
            assert f"{budget:,}" in prompt
            numbers = re.findall(r"\d[\d,]*", prompt.replace(s.arrival, ""))
            assert all(n.replace(",", "") in (str(budget), "100") for n in numbers), numbers
            assert not re.search(r"\b" + str(s.floor_price) + r"\b", prompt)
