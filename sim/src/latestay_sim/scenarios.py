"""The scenario grid: list price, floor, slot type and arrival time."""

from __future__ import annotations

import random
from dataclasses import dataclass

LIST_PRICES = tuple(range(1000, 3001, 250))
FLOOR_PERCENTS = (40, 50, 60, 70)
# The engine only accepts arrivals inside a slot's window (LATE_NIGHT 21:00-03:00, EARLY_MORNING 04:00-09:00).
SLOT_ARRIVALS = (
    ("LATE_NIGHT", "21:30"),
    ("LATE_NIGHT", "23:00"),
    ("LATE_NIGHT", "00:45"),
    ("EARLY_MORNING", "05:30"),
)


@dataclass(frozen=True)
class Scenario:
    list_price: int
    floor_percent: int
    slot_type: str
    arrival: str

    @property
    def floor_price(self) -> int:
        return round(self.list_price * self.floor_percent / 100)


def grid() -> list[Scenario]:
    return [
        Scenario(list_price, floor_percent, slot, arrival)
        for list_price in LIST_PRICES
        for floor_percent in FLOOR_PERCENTS
        for slot, arrival in SLOT_ARRIVALS
    ]


def sample(n: int, rng: random.Random) -> list[Scenario]:
    """n scenarios drawn uniformly from the grid, with replacement."""
    cells = grid()
    return [rng.choice(cells) for _ in range(n)]
