# Pricing compare: old policy vs occupancy pricing

2026-10-30 16:33 · 400 scenarios, each run under both policies · seed 7 · scripted personas (deterministic, no LLM) · http://localhost:8090

- **Old (version 1):** the engine before this change. Opening offer by arrival time only (90/80/70/60% of list), concessions of 40/25/15/10% of the gap.
- **New (version 2):** the same, then the night: the last free room opens at 95% of list or more, a hotel with a quarter of its rooms or fewer free opens 10 points higher and concedes half as fast, a half-empty one opens 10 points lower; the opening is scaled by the night's demand (weekend 110%, a season's multiplier) and capped at list. Floors never move.
- **Same scenarios:** list ₹1,000–3,000, floors 40–70% of list, the four arrival times, 10 rooms in all with 1, 2, 4 or 8 free, on a weekday (100%), weekend (110%), peak-season (130%) or off-season (85%) night. Each scenario keeps its persona, hidden budget and random seed in both runs.
- **RevPAR** here is agreed revenue ÷ conversations: each conversation stands for one room the hotel could sell that night.

## Overall

| | Old | New | Change |
| --- | ---: | ---: | ---: |
| Deal rate | 57.2% | 41.0% | −16.2 pts |
| Average agreed price | ₹1,227 | ₹1,277 | +₹49 |
| Agreed, % of list | 60.7% | 65.0% | +4.3 pts |
| RevPAR | ₹703 | ₹523 | −₹179 |
| Deals | 229 | 164 | |
| Floor violations | 0 | 0 | |
| Replies naming another price than the engine's | 0 | 0 | |
| Errors | 0 | 0 | |

## By rooms free (out of 10)

| | Conversations | Deal rate (old → new) | Avg agreed price (old → new) | RevPAR (old → new) | RevPAR change |
| --- | ---: | --- | --- | --- | ---: |
| 1 free (last room) | 100 | 56.0% → 20.0% | ₹1,274 → ₹1,940 | ₹714 → ₹388 | −₹326 |
| 2 free (scarce) | 100 | 57.0% → 37.0% | ₹1,217 → ₹1,276 | ₹694 → ₹472 | −₹222 |
| 4 free (in between) | 100 | 60.0% → 52.0% | ₹1,199 → ₹1,190 | ₹720 → ₹619 | −₹100 |
| 8 free (half empty) | 100 | 56.0% → 55.0% | ₹1,221 → ₹1,117 | ₹684 → ₹614 | −₹70 |

## By night

| | Conversations | Deal rate (old → new) | Avg agreed price (old → new) | RevPAR (old → new) | RevPAR change |
| --- | ---: | --- | --- | --- | ---: |
| weekday (100%) | 105 | 52.4% → 41.9% | ₹1,222 → ₹1,244 | ₹640 → ₹521 | −₹119 |
| weekend (110%) | 108 | 56.5% → 37.0% | ₹1,244 → ₹1,322 | ₹703 → ₹490 | −₹213 |
| peak season (130%) | 94 | 60.6% → 29.8% | ₹1,232 → ₹1,488 | ₹747 → ₹443 | −₹304 |
| off season (85%) | 93 | 60.2% → 55.9% | ₹1,209 → ₹1,155 | ₹728 → ₹646 | −₹82 |

## By persona

| | Conversations | Deal rate (old → new) | Avg agreed price (old → new) | RevPAR (old → new) | RevPAR change |
| --- | ---: | --- | --- | --- | ---: |
| student | 40 | 40.0% → 25.0% | ₹1,081 → ₹950 | ₹432 → ₹238 | −₹195 |
| hard haggler | 40 | 55.0% → 27.5% | ₹959 → ₹941 | ₹528 → ₹259 | −₹269 |
| family | 40 | 87.5% → 55.0% | ₹1,193 → ₹1,198 | ₹1,044 → ₹659 | −₹385 |
| hinglish | 40 | 52.5% → 37.5% | ₹1,188 → ₹1,193 | ₹624 → ₹448 | −₹176 |
| punjabi | 40 | 60.0% → 40.0% | ₹1,119 → ₹1,016 | ₹671 → ₹406 | −₹265 |
| fast accepter | 40 | 100.0% → 92.5% | ₹1,395 → ₹1,534 | ₹1,395 → ₹1,419 | +₹24 |
| questioner | 40 | 57.5% → 45.0% | ₹1,283 → ₹1,256 | ₹738 → ₹565 | −₹172 |
| lowballer | 40 | 0.0% → 0.0% | – → – | ₹0 → ₹0 | ±₹0 |
| injector | 40 | 52.5% → 45.0% | ₹1,321 → ₹1,575 | ₹694 → ₹709 | +₹15 |
| topic changer | 40 | 67.5% → 42.5% | ₹1,335 → ₹1,253 | ₹901 → ₹532 | −₹369 |

## What this does and doesn't show

- The personas' budgets are a share of list price and don't react to weekends, seasons or how full the hotel is. Real travellers pay more on a busy night, so this measures how the policy behaves against a fixed willingness to pay, not real demand.
- A room not sold in one conversation could still sell to the next traveller on a busy night; the RevPAR here counts it as unsold.
- Both runs went through the backend's public API. The simulator checks every offer against the floor and every reply's number against the engine's.
