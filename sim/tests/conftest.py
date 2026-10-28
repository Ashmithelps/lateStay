from __future__ import annotations

import itertools
import uuid

import pytest

from latestay_sim.backend import Reply, SessionClosed
from latestay_sim.scenarios import Scenario


class FakeBackend:
    """A tiny stand-in for the backend: opens at 80% of list, concedes 50 per counter, never below floor."""

    def __init__(self, reply_template: str = "The room is ₹{offer:,} for tonight.", ignore_floor: bool = False):
        self.reply_template = reply_template
        self.ignore_floor = ignore_floor
        self.sessions: dict[str, dict] = {}
        self.messages: list[str] = []

    def create_session(self, scenario: Scenario) -> dict:
        session_id = str(uuid.uuid4())
        offer = max(scenario.floor_price, round(scenario.list_price * 0.8 / 50) * 50)
        self.sessions[session_id] = {"offer": offer, "floor": scenario.floor_price, "status": "OPEN", "rounds": 0}
        return {"id": session_id, "currentOffer": offer, "status": "OPEN"}

    def send(self, session_id: str, text: str) -> Reply:
        self.messages.append(text)
        s = self.sessions[session_id]
        if s["status"] in ("AGREED", "NO_DEAL"):
            raise SessionClosed(s["status"])
        lowered = text.lower()
        if any(w in lowered for w in ("deal", "done", "i'll take it", "pakka", "theek hai")):
            s["status"] = "AGREED"
        elif s["status"] == "FINAL_OFFER":
            s["status"] = "NO_DEAL"
        else:
            s["rounds"] += 1
            floor = 0 if self.ignore_floor else s["floor"]
            s["offer"] = max(floor, s["offer"] - 50 if not self.ignore_floor else s["offer"] // 2)
            if s["offer"] == floor or s["rounds"] >= 4:
                s["status"] = "FINAL_OFFER"
        return Reply(self.reply_template.format(offer=s["offer"]), s["offer"], s["status"], "MEETING_HALFWAY",
                     "COUNTER", "TEMPLATE", 2, 0, 0, 12.5)


@pytest.fixture
def scenario() -> Scenario:
    return Scenario(list_price=2000, floor_percent=50, slot_type="LATE_NIGHT", arrival="23:00")


@pytest.fixture
def counter():
    return itertools.count()
