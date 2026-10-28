import json
import random
from types import SimpleNamespace

from latestay_sim.personas import PERSONAS_BY_KEY
from latestay_sim.traveller import Exchange, LlmTraveller, ScriptedTraveller


def scripted(key: str, budget: int = 1000) -> ScriptedTraveller:
    return ScriptedTraveller(PERSONAS_BY_KEY[key], budget, random.Random(1))


def test_scripted_never_accepts_above_budget():
    for key in PERSONAS_BY_KEY:
        traveller = scripted(key, budget=900)
        for offer in (2000, 1200, 950, 901):
            for status in ("OPEN", "FINAL_OFFER"):
                assert traveller.next_turn([], offer, status).action != "accept"


def test_fast_accepter_accepts_within_budget_immediately():
    assert scripted("fast_accepter").next_turn([], 950, "OPEN").action == "accept"


def test_lowballer_opens_at_100():
    assert "100" in scripted("lowballer").next_turn([], 1600, "OPEN").message


def test_injector_tries_an_injection_first():
    message = scripted("injector").next_turn([], 1600, "OPEN").message.lower()
    assert any(w in message for w in ("ignore", "system", "admin", "minimum", "accept", "recent_messages"))


class FakeAnthropic:
    def __init__(self, responses):
        self.calls = []
        self._responses = iter(responses)
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    def _create(self, **kwargs):
        self.calls.append(kwargs)
        return next(self._responses)


def response(text: str | None, stop_reason: str = "end_turn"):
    content = [SimpleNamespace(type="text", text=text)] if text is not None else []
    return SimpleNamespace(content=content, stop_reason=stop_reason,
                           usage=SimpleNamespace(input_tokens=300, output_tokens=40))


def llm_traveller(client, model, scenario, budget=1000):
    persona = PERSONAS_BY_KEY["hinglish"]
    return LlmTraveller(client, model, persona, budget, scenario, ScriptedTraveller(persona, budget, random.Random(1)))


def test_llm_traveller_parses_json_and_counts_tokens(scenario):
    client = FakeAnthropic([response(json.dumps({"message": "700 mein de do", "action": "talk"}))])
    traveller = llm_traveller(client, "claude-opus-5-5", scenario)

    turn = traveller.next_turn([], 1600, "OPEN")

    assert (turn.message, turn.action) == ("700 mein de do", "talk")
    assert (traveller.usage.input_tokens, traveller.usage.output_tokens) == (300, 40)
    call = client.calls[0]
    assert call["fallbacks"] == "default"
    assert call["betas"] == ["server-side-fallback-2026-07-01"]
    assert call["output_config"]["effort"] == "low"
    assert str(scenario.floor_price) not in call["system"]


def test_no_fallback_param_for_haiku(scenario):
    client = FakeAnthropic([response(json.dumps({"message": "ok", "action": "accept"}))])
    llm_traveller(client, "claude-haiku-5-5", scenario).next_turn([], 900, "OPEN")

    assert "fallbacks" not in client.calls[0]
    assert "betas" not in client.calls[0]


def test_refusal_and_bad_json_fall_back_to_script(scenario):
    client = FakeAnthropic([response(None, "refusal"), response("not json")])
    traveller = llm_traveller(client, "claude-opus-5-5", scenario)

    traveller.next_turn([], 1600, "OPEN")
    traveller.next_turn([], 1600, "OPEN")

    assert traveller.usage.refusals == 1
    assert traveller.usage.fallbacks_to_script == 2


def test_history_alternates_roles_and_shows_offer(scenario):
    client = FakeAnthropic([response(json.dumps({"message": "hi", "action": "talk"}))] * 2)
    traveller = llm_traveller(client, "claude-opus-5-5", scenario)
    traveller.next_turn([], 1600, "OPEN")
    history = [Exchange("700?", "talk", "₹1,400 tonight.", 1400, "OPEN")]

    traveller.next_turn(history, 1400, "OPEN")

    roles = [m["role"] for m in client.calls[1]["messages"]]
    assert roles == ["user", "assistant", "user"]
    assert "₹1,600" in client.calls[1]["messages"][0]["content"]
    assert "₹1,400" in client.calls[1]["messages"][2]["content"]
