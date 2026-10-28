"""Writes report.md, results.csv and the agreed-price chart."""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

from latestay_sim.metrics import Summary, pct_above_floor, pct_of_list  # noqa: E402
from latestay_sim.personas import PERSONAS_BY_KEY  # noqa: E402
from latestay_sim.pricing import cost_usd  # noqa: E402
from latestay_sim.runner import ConversationResult  # noqa: E402

# docs/DESIGN.md tokens
INK = "#222222"
INK_SECONDARY = "#6A6A6A"
HAIRLINE = "#DDDDDD"
EMBER = "#D9572B"

CHART_FILE = "agreed_vs_budget.png"


@dataclass(frozen=True)
class RunInfo:
    timestamp: str
    n: int
    seed: int
    persona_model: str | None  # None when offline
    backend_model: str
    concurrency: int
    base_url: str
    postgis: str | None


def _fmt(value: float | None, suffix: str = "", digits: int = 1) -> str:
    return "–" if value is None else f"{value:.{digits}f}{suffix}"


def _money(value: float | None) -> str:
    return "–" if value is None else f"${value:.4f}" if value < 1 else f"${value:.2f}"


def write_csv(results: list[ConversationResult], path: Path, persona_model: str | None, backend_model: str) -> None:
    columns = [
        "conversation", "persona", "list_price", "floor_percent", "floor_price", "slot_type", "arrival", "budget",
        "opening_offer", "outcome", "agreed_price", "final_offer", "agreed_pct_of_list", "agreed_pct_above_floor",
        "traveller_turns", "questions", "floor_violations", "template_replies", "number_mismatches",
        "over_budget", "latency_mean_ms", "latency_max_ms", "persona_input_tokens", "persona_output_tokens",
        "persona_refusals", "persona_scripted_fallbacks", "backend_llm_calls", "backend_input_tokens",
        "backend_output_tokens", "approx_cost_usd", "session_id", "error",
    ]
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(columns)
        for r in results:
            latencies = [t.reply.latency_ms for t in r.turns]
            b_in = sum(t.reply.llm_input_tokens for t in r.turns)
            b_out = sum(t.reply.llm_output_tokens for t in r.turns)
            u = r.traveller_usage
            persona_cost = cost_usd(persona_model, u.input_tokens, u.output_tokens) if persona_model else 0
            cost = (persona_cost or 0) + (cost_usd(backend_model, b_in, b_out) or 0)
            writer.writerow([
                r.number, r.persona.key, r.scenario.list_price, r.scenario.floor_percent, r.floor,
                r.scenario.slot_type, r.scenario.arrival, r.budget, r.opening_offer, r.outcome, r.agreed_price,
                r.final_offer,
                f"{pct_of_list(r):.1f}" if r.agreed else "", f"{pct_above_floor(r):.1f}" if r.agreed else "",
                len(r.turns), r.questions, r.floor_violations, r.template_replies, r.mismatches, r.over_budget,
                f"{sum(latencies) / len(latencies):.0f}" if latencies else "",
                f"{max(latencies):.0f}" if latencies else "",
                u.input_tokens, u.output_tokens, u.refusals, u.fallbacks_to_script,
                sum(t.reply.llm_calls for t in r.turns), b_in, b_out, f"{cost:.5f}", r.session_id or "", r.error or "",
            ])


def write_chart(results: list[ConversationResult], path: Path) -> None:
    deals = [r for r in results if r.agreed]
    fig, ax = plt.subplots(figsize=(8, 5), dpi=200, facecolor="white")
    ax.set_facecolor("white")

    budgets = [r.budget for r in results] or [0, 1]
    low, high = min(budgets) * 0.9, max(budgets) * 1.05
    ax.plot([low, high], [low, high], color=INK, linewidth=1.2, label="Price = budget")
    ax.scatter([r.budget for r in deals], [r.agreed_price for r in deals], s=22, color=EMBER, alpha=0.85,
               linewidths=0, label=f"Agreed deals ({len(deals)})", zorder=3)

    ax.set_title("Agreed price vs traveller budget", color=INK, fontsize=12, loc="left", pad=12)
    ax.set_xlabel("Traveller's hidden budget (₹)", color=INK_SECONDARY, fontsize=9)
    ax.set_ylabel("Agreed price (₹)", color=INK_SECONDARY, fontsize=9)
    ax.grid(color=HAIRLINE, linewidth=0.6)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(INK)
    ax.tick_params(colors=INK, labelsize=8)
    ax.legend(frameon=False, fontsize=8, labelcolor=INK)
    fig.tight_layout()
    fig.savefig(path, facecolor="white")
    plt.close(fig)


def _gate(value: int) -> str:
    return "PASS" if value == 0 else "FAIL"


def _summary_rows(s: Summary) -> list[tuple[str, str]]:
    return [
        ("Conversations", f"{s.conversations} ({s.errors} errors)"),
        ("Deal rate", _fmt(s.deal_rate, "%")),
        ("Agreed price, % of list", _fmt(s.agreed_pct_of_list, "%")),
        ("Agreed price, % above floor", _fmt(s.agreed_pct_above_floor, "%")),
        ("Average traveller turns", _fmt(s.avg_turns)),
        ("Floor violations (must be 0)", f"{s.floor_violations} {_gate(s.floor_violations)}"),
        ("Reply number mismatches (must be 0)", f"{s.mismatches} {_gate(s.mismatches)}"),
        ("Template fallbacks", f"{s.template_replies} of {s.replies} replies"),
        ("Deals above the persona's budget (persona error)", str(s.over_budget_deals)),
        ("Latency p50 / p95", f"{_fmt(s.latency_p50_ms, ' ms', 0)} / {_fmt(s.latency_p95_ms, ' ms', 0)}"),
        ("Persona tokens in / out", f"{s.persona_input_tokens:,} / {s.persona_output_tokens:,}"),
        ("Backend LLM tokens in / out", f"{s.backend_input_tokens:,} / {s.backend_output_tokens:,}"),
        ("Approx. cost (persona + backend)",
         f"{_money(s.total_cost_usd)} ({_money(s.persona_cost_usd)} + {_money(s.backend_cost_usd)})"),
    ]


def pick_samples(results: list[ConversationResult]) -> list[tuple[str, ConversationResult]]:
    started = [r for r in results if r.turns]
    picked: list[tuple[str, ConversationResult]] = []

    def add(label: str, candidates: list[ConversationResult], key=None, reverse=False) -> None:
        candidates = [c for c in candidates if all(c is not p for _, p in picked)]
        if candidates:
            chosen = sorted(candidates, key=key, reverse=reverse)[0] if key else candidates[0]
            picked.append((label, chosen))

    deals = [r for r in started if r.agreed]
    add("Best for the hotel: highest price above floor", deals, key=pct_above_floor, reverse=True)
    missed = [r for r in started if not r.agreed and r.budget >= r.floor]
    if missed:
        add("Worst: a sale missed although the budget covered the floor", missed,
            key=lambda r: r.budget - r.floor, reverse=True)
    else:
        add("Worst: lowest price above floor", deals, key=pct_above_floor)
    add("Prompt-injection attempt", [r for r in started if r.persona.key == "injector"])
    add("Hinglish or Punjabi", [r for r in started if r.persona.language != "english"])
    add("Most questions", started, key=lambda r: r.questions, reverse=True)
    return picked


def _transcript(r: ConversationResult) -> str:
    s = r.scenario
    lines = [
        f"{r.persona.name}. List ₹{s.list_price:,}, floor ₹{r.floor:,} ({s.floor_percent}%), {s.slot_type} "
        f"arriving {s.arrival}, hidden budget ₹{r.budget:,}. Outcome **{r.outcome}**"
        + (f" at ₹{r.agreed_price:,}" if r.agreed_price else "") + ".",
        "",
        f"- *Opening offer: ₹{r.opening_offer:,}*",
    ]
    for t in r.turns:
        flags = [t.reply.status, t.reply.reply_source or "?"]
        if t.mismatch:
            flags.append("NUMBER MISMATCH")
        if t.below_floor:
            flags.append("BELOW FLOOR")
        lines.append(f"- **Traveller** ({t.action}): {_md(t.traveller)}")
        lines.append(f"- **Hotel** (₹{t.reply.offer:,}, {', '.join(flags)}): {_md(t.reply.reply)}")
    return "\n".join(lines)


def _md(text: str, limit: int = 400) -> str:
    text = " ".join(text.split()).replace("|", "\\|").replace("<", "&lt;")
    return text if len(text) <= limit else text[:limit] + f"… ({len(text)} chars)"


def write_markdown(results: list[ConversationResult], overall: Summary, personas: dict[str, Summary],
                   info: RunInfo, path: Path) -> None:
    out = [
        f"# LateStay negotiation simulation, {info.timestamp}",
        "",
        f"- Conversations: {info.n}, seed {info.seed}, concurrency {info.concurrency}",
        f"- Personas: {info.persona_model or 'offline (scripted personas, no LLM)'}",
        f"- Backend: {info.base_url} (PostGIS {info.postgis or '?'}); costs assume the backend runs "
        f"{info.backend_model}",
        "",
        "## Summary",
        "",
        "| Metric | Value |",
        "| --- | --- |",
        *[f"| {k} | {v} |" for k, v in _summary_rows(overall)],
        "",
        "## By persona",
        "",
        "| Persona | N | Deal rate | Agreed % of list | % above floor | Avg turns | Floor violations "
        "| Template replies | Mismatches | p50 ms | p95 ms | Cost |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for key, s in personas.items():
        out.append(
            f"| {PERSONAS_BY_KEY[key].name} | {s.conversations} | {_fmt(s.deal_rate, '%')} "
            f"| {_fmt(s.agreed_pct_of_list, '%')} | {_fmt(s.agreed_pct_above_floor, '%')} | {_fmt(s.avg_turns)} "
            f"| {s.floor_violations} | {s.template_replies}/{s.replies} | {s.mismatches} "
            f"| {_fmt(s.latency_p50_ms, '', 0)} | {_fmt(s.latency_p95_ms, '', 0)} | {_money(s.total_cost_usd)} |")
    out += [
        "",
        f"![Agreed price vs traveller budget]({CHART_FILE})",
        "",
        "## Sample transcripts",
        "",
    ]
    for label, r in pick_samples(results):
        out += [f"### {label}", "", _transcript(r), ""]
    out += [
        "## How to read this",
        "",
        "- Floor violations: any offer or agreed price below the floor the simulator set. Must be 0.",
        "- Number mismatches: the simulator's own extractor found an amount in a reply other than the engine's "
        "offer (or none). Must be 0.",
        "- Template fallbacks come from the backend's `replySource`; with no backend LLM every reply is a template.",
        "- Deals above budget mean the persona model agreed to more than its hidden budget: a persona error, "
        "not a hotel one.",
        "- Latency is per traveller message, measured by the simulator, with the concurrency above.",
        "- Costs are approximate, from token counts and the table in `pricing.py`.",
        "",
    ]
    path.write_text("\n".join(out), encoding="utf-8")


def write_report(results: list[ConversationResult], overall: Summary, personas: dict[str, Summary],
                 info: RunInfo, directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    write_csv(results, directory / "results.csv", info.persona_model, info.backend_model)
    write_chart(results, directory / CHART_FILE)
    report = directory / "report.md"
    write_markdown(results, overall, personas, info, report)
    return report
