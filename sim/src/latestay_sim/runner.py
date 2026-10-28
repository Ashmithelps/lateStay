"""Runs one simulated conversation against the backend."""

from __future__ import annotations

import itertools
from dataclasses import dataclass, field

from latestay_sim.backend import BackendClient, BackendError, Reply, SessionClosed
from latestay_sim.checks import number_mismatch
from latestay_sim.personas import Persona
from latestay_sim.scenarios import Scenario
from latestay_sim.traveller import Exchange, Traveller, TravellerUsage

MAX_TURNS = 8
_ids = itertools.count(1)


@dataclass
class Turn:
    traveller: str
    action: str
    reply: Reply
    mismatch: bool
    below_floor: bool


@dataclass
class ConversationResult:
    number: int
    persona: Persona
    scenario: Scenario
    budget: int
    session_id: str | None = None
    opening_offer: int | None = None
    turns: list[Turn] = field(default_factory=list)
    outcome: str = "ERROR"  # AGREED, NO_DEAL, WALKED_AWAY, MAX_TURNS, CLOSED, ERROR
    agreed_price: int | None = None
    final_offer: int | None = None
    traveller_usage: TravellerUsage = field(default_factory=TravellerUsage)
    error: str | None = None

    @property
    def floor(self) -> int:
        return self.scenario.floor_price

    @property
    def agreed(self) -> bool:
        return self.outcome == "AGREED"

    @property
    def floor_violations(self) -> int:
        opening = 1 if self.opening_offer is not None and self.opening_offer < self.floor else 0
        return opening + sum(t.below_floor for t in self.turns)

    @property
    def mismatches(self) -> int:
        return sum(t.mismatch for t in self.turns)

    @property
    def template_replies(self) -> int:
        return sum(t.reply.reply_source == "TEMPLATE" for t in self.turns)

    @property
    def questions(self) -> int:
        return sum(t.reply.intent == "QUESTION" for t in self.turns)

    @property
    def over_budget(self) -> bool:
        return self.agreed_price is not None and self.agreed_price > self.budget


def run_conversation(backend: BackendClient, traveller: Traveller, persona: Persona, scenario: Scenario,
                     budget: int, max_turns: int = MAX_TURNS) -> ConversationResult:
    result = ConversationResult(next(_ids), persona, scenario, budget)
    result.traveller_usage = traveller.usage
    try:
        session = backend.create_session(scenario)
    except BackendError as e:
        result.error = str(e)
        return result

    result.session_id = session["id"]
    offer, status = session["currentOffer"], session["status"]
    result.opening_offer = result.final_offer = offer
    history: list[Exchange] = []
    result.outcome = "MAX_TURNS"

    for _ in range(max_turns):
        turn = traveller.next_turn(history, offer, status)
        try:
            reply = backend.send(result.session_id, turn.message)
        except SessionClosed as e:
            result.outcome = "CLOSED"
            result.error = f"session closed: {e}"
            break
        except BackendError as e:
            result.outcome, result.error = "ERROR", str(e)
            break

        offer, status = reply.offer, reply.status
        result.final_offer = offer
        result.turns.append(Turn(turn.message, turn.action, reply,
                                 mismatch=number_mismatch(reply.reply, reply.offer),
                                 below_floor=reply.offer < scenario.floor_price))
        history.append(Exchange(turn.message, turn.action, reply.reply, offer, status))

        if status == "AGREED":
            result.outcome, result.agreed_price = "AGREED", offer
            break
        if status in ("NO_DEAL", "EXPIRED"):
            result.outcome = status
            break
        if turn.action == "leave":
            result.outcome = "WALKED_AWAY"
            break
    return result
