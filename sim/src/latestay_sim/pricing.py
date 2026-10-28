"""Approximate USD prices per million tokens (input, output). Update when pricing changes."""

PRICES_PER_MTOK: dict[str, tuple[float, float]] = {
    "claude-opus-5-5": (4.00, 20.00),
    "claude-sonnet-5-5": (2.00, 10.00),
    "claude-haiku-5-5": (0.10, 0.50),
}


def cost_usd(model: str, input_tokens: int, output_tokens: int) -> float | None:
    prices = PRICES_PER_MTOK.get(model)
    if prices is None:
        return None
    return (input_tokens * prices[0] + output_tokens * prices[1]) / 1_000_000
