"""Independent checks on the hotel's replies, deliberately separate from the backend's own validator."""

from __future__ import annotations

import re

MIN_BARE = 100
MAX_BARE = 99_999

_DIGITS = r"(\d{1,3}(?:,\d{2,3})+|\d+)(?:\.(\d+))?"
_CURRENCY = re.compile(r"(?:₹|\brs\.?|\binr\b)\s*" + _DIGITS + r"\s*(k\b)?")
_MULTIPLIED = re.compile(r"(?<![\d.,:])" + _DIGITS + r"\s*(k|sau|hundred|hazaar|hazar|hajar|thousand)\b")
_BARE = re.compile(r"(?<![\d.,:])" + _DIGITS + r"(?![\d:]|\.\d|\s*(?:am|pm)\b)")
_WORD = re.compile(r"[a-z]+")

_HINDI_UNITS = {
    "ek": 1, "do": 2, "teen": 3, "char": 4, "chaar": 4, "paanch": 5, "panch": 5, "panj": 5, "chhe": 6,
    "che": 6, "saat": 7, "sat": 7, "satt": 7, "aath": 8, "ath": 8, "atth": 8, "nau": 9, "das": 10,
    "dedh": 1.5, "dhai": 2.5, "dhaai": 2.5, "pachas": 50, "pachaas": 50,
}
_HINDI_MODIFIERS = {"saade": 0.5, "sade": 0.5, "sadhe": 0.5, "sava": 0.25, "sawa": 0.25, "paune": -0.25}
_HINDI_MULTIPLIERS = {"sau": 100, "hazaar": 1000, "hazar": 1000, "hajar": 1000}
_ENGLISH = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9,
    "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16,
    "seventeen": 17, "eighteen": 18, "nineteen": 19, "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50,
    "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
}


def _amount(whole: str, fraction: str | None, multiplier: int) -> int:
    return round(float(whole.replace(",", "") + ("." + fraction if fraction else "")) * multiplier)


def _mask(text: str, start: int, end: int) -> str:
    return text[:start] + " " * (end - start) + text[end:]


def _read_hindi(words: list[str]) -> int:
    total, current, modifier, saw_multiplier = 0.0, None, 0.0, False
    for w in words:
        if w in _HINDI_MODIFIERS:
            modifier = _HINDI_MODIFIERS[w]
        elif w in _HINDI_UNITS:
            current, modifier = _HINDI_UNITS[w] + modifier, 0.0
        elif w in _HINDI_MULTIPLIERS:
            total += (1 if current is None else current) * _HINDI_MULTIPLIERS[w]
            current, saw_multiplier = None, True
        elif w != "and":
            return 0
    return round(total + (current or 0)) if saw_multiplier else 0


def _read_english(words: list[str]) -> int:
    if len(words) == 2 and all(w in _ENGLISH for w in words):
        hundreds, rest = _ENGLISH[words[0]], _ENGLISH[words[1]]
        if hundreds < 20 and rest >= 10:
            return hundreds * 100 + rest
    total = current = 0
    for w in words:
        if w in _ENGLISH:
            current += _ENGLISH[w]
        elif w == "hundred":
            current = (current or 1) * 100
        elif w == "thousand":
            total, current = total + (current or 1) * 1000, 0
        elif w != "and":
            return 0
    return total + current


def _is_number_word(w: str) -> bool:
    return (w in _HINDI_UNITS or w in _HINDI_MODIFIERS or w in _HINDI_MULTIPLIERS or w in _ENGLISH
            or w in ("hundred", "thousand"))


def _word_amounts(text: str) -> list[tuple[int, int]]:
    found: list[tuple[int, int]] = []
    run: list[str] = []
    start = -1

    def flush() -> None:
        while run and run[-1] == "and":
            run.pop()
        for i in range(len(run)):
            value = max(_read_hindi(run[i:]), _read_english(run[i:]))
            if value >= MIN_BARE:
                found.append((start, value))
                break
        run.clear()

    for match in _WORD.finditer(text):
        word = match.group()
        if _is_number_word(word) or (run and word == "and"):
            if not run:
                start = match.start()
            run.append(word)
        else:
            flush()
    flush()
    return found


def money_amounts(text: str) -> list[int]:
    """Distinct money-looking amounts in a reply, in order of appearance."""
    lowered = text.lower()
    found: list[tuple[int, int]] = []
    for pattern, kind in ((_CURRENCY, "currency"), (_MULTIPLIED, "multiplied")):
        while match := pattern.search(lowered):
            if kind == "currency":
                value = _amount(match.group(1), match.group(2), 1000 if match.group(3) else 1)
            else:
                value = _amount(match.group(1), match.group(2), 100 if match.group(3) in ("sau", "hundred") else 1000)
            if value > 0:
                found.append((match.start(), value))
            lowered = _mask(lowered, match.start(), match.end())
    for match in _BARE.finditer(lowered):
        value = _amount(match.group(1), match.group(2), 1)
        if MIN_BARE <= value <= MAX_BARE:
            found.append((match.start(), value))
    found.extend(_word_amounts(lowered))
    found.sort()
    seen: list[int] = []
    for _, value in found:
        if value not in seen:
            seen.append(value)
    return seen


def number_mismatch(reply: str, offer: int) -> bool:
    """True if the reply mentions any amount other than the engine's offer, or doesn't mention the offer."""
    return money_amounts(reply) != [offer]
