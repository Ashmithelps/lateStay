import pytest

from latestay_sim.checks import money_amounts, number_mismatch


@pytest.mark.parametrize(("text", "expected"), [
    ("The room is ₹1,000 for tonight.", [1000]),
    ("Rs. 900 final", [900]),
    ("INR 850", [850]),
    ("1.2k", [1200]),
    ("saat sau mein ho jayega", [700]),
    ("saade saat sau", [750]),
    ("dedh hazaar", [1500]),
    ("seven fifty", [750]),
    ("one thousand two hundred", [1200]),
    ("₹1,000, down from ₹1,200", [1000, 1200]),
    ("₹1,000? ₹1,000.", [1000]),
    ("Rs 50 extra", [50]),
    ("₹12,500 tonight", [12500]),
])
def test_finds_money(text, expected):
    assert money_amounts(text) == expected


@pytest.mark.parametrize("text", [
    "check in by 11:30 pm", "2 log, 1 room", "call 9876543210", "leave by ten in the morning", "de do bhai",
])
def test_ignores_non_money(text):
    assert money_amounts(text) == []


def test_mismatch():
    assert not number_mismatch("Deal at ₹900.", 900)
    assert number_mismatch("Deal at ₹900, floor ₹700.", 900)
    assert number_mismatch("Deal at ₹800.", 900)
    assert number_mismatch("Deal done.", 900)
