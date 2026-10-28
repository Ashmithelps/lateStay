"""Aggregate metrics over conversations."""

from __future__ import annotations

import math
from dataclasses import dataclass
from statistics import mean

from latestay_sim.pricing import cost_usd
from latestay_sim.runner import ConversationResult


def percentile(values: list[float], p: float) -> float | None:
    """Nearest-rank percentile."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(p / 100 * len(ordered)))
    return ordered[rank - 1]


def pct_of_list(r: ConversationResult) -> float:
    return r.agreed_price / r.scenario.list_price * 100


def pct_above_floor(r: ConversationResult) -> float:
    return (r.agreed_price - r.floor) / r.floor * 100


@dataclass
class Summary:
    conversations: int
    errors: int
    deals: int
    deal_rate: float
    agreed_pct_of_list: float | None
    agreed_pct_above_floor: float | None
    avg_turns: float
    floor_violations: int
    replies: int
    template_replies: int
    mismatches: int
    over_budget_deals: int
    latency_p50_ms: float | None
    latency_p95_ms: float | None
    persona_input_tokens: int
    persona_output_tokens: int
    backend_input_tokens: int
    backend_output_tokens: int
    persona_cost_usd: float | None
    backend_cost_usd: float | None

    @property
    def total_cost_usd(self) -> float | None:
        if self.persona_cost_usd is None and self.backend_cost_usd is None:
            return None
        return (self.persona_cost_usd or 0) + (self.backend_cost_usd or 0)


def summarize(results: list[ConversationResult], persona_model: str | None, backend_model: str) -> Summary:
    deals = [r for r in results if r.agreed]
    turns = [t for r in results for t in r.turns]
    latencies = [t.reply.latency_ms for t in turns]
    p_in = sum(r.traveller_usage.input_tokens for r in results)
    p_out = sum(r.traveller_usage.output_tokens for r in results)
    b_in = sum(t.reply.llm_input_tokens for t in turns)
    b_out = sum(t.reply.llm_output_tokens for t in turns)
    started = [r for r in results if r.session_id]
    return Summary(
        conversations=len(results),
        errors=sum(r.outcome == "ERROR" for r in results),
        deals=len(deals),
        deal_rate=len(deals) / len(results) * 100 if results else 0.0,
        agreed_pct_of_list=mean(map(pct_of_list, deals)) if deals else None,
        agreed_pct_above_floor=mean(map(pct_above_floor, deals)) if deals else None,
        avg_turns=mean(len(r.turns) for r in started) if started else 0.0,
        floor_violations=sum(r.floor_violations for r in results),
        replies=len(turns),
        template_replies=sum(r.template_replies for r in results),
        mismatches=sum(r.mismatches for r in results),
        over_budget_deals=sum(r.over_budget for r in results),
        latency_p50_ms=percentile(latencies, 50),
        latency_p95_ms=percentile(latencies, 95),
        persona_input_tokens=p_in,
        persona_output_tokens=p_out,
        backend_input_tokens=b_in,
        backend_output_tokens=b_out,
        persona_cost_usd=cost_usd(persona_model, p_in, p_out) if persona_model else 0.0,
        backend_cost_usd=cost_usd(backend_model, b_in, b_out),
    )


def by_persona(results: list[ConversationResult], persona_model: str | None,
               backend_model: str) -> dict[str, Summary]:
    groups: dict[str, list[ConversationResult]] = {}
    for r in results:
        groups.setdefault(r.persona.key, []).append(r)
    return {key: summarize(group, persona_model, backend_model) for key, group in groups.items()}
