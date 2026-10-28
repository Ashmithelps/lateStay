# WhatsApp message templates

LateStay talks to hotel owners through the WhatsApp Business Cloud API. A message LateStay starts (rather than one that answers the owner within 24 hours of their last message) must use a template that Meta has approved. Submit these three in **WhatsApp Manager > Message templates > Create template**, exactly as written: the backend sends them by name with this number of variables and buttons in this order (`backend/src/main/java/com/latestay/whatsapp/WhatsAppMessages.java`).

All three:

- **Category:** Utility (they are about something the owner signed up for: their availability and bookings, with no promotion).
- **Language:** English (`en`). The backend sends `language.code = en`; if you approve them as `en_US`, change `latestay.whatsapp.language`.
- **Header and footer:** none.
- **Variables:** numbered (`{{1}}`, `{{2}}`…). Meta asks for a sample value for each one when you submit; use the samples below.

## 1. `nightly_availability`

Sent at 21:00 Asia/Kolkata to every active hotel, and when an admin presses "Send availability ping now".

**Body**

```
Good evening {{1}}. How many rooms are free tonight for late arrivals?
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | Hotel name | `Pine Crest` |

**Buttons:** four **Quick reply** buttons, in this order:

| # | Button text |
| --- | --- |
| 1 | `0` |
| 2 | `1` |
| 3 | `2` |
| 4 | `3+` |

Each button carries a payload the backend sets when sending (hotel id, night, rooms), so a tap updates the right hotel and night. `3+` is stored as 3 rooms; owners can type an exact number ("rooms 5", "5 room khali") instead.

## 2. `booking_request`

Sent the moment a traveller's deposit is paid (booking status DEPOSIT_PAID). The owner has 5 minutes to answer; after that the booking times out and the traveller is refunded.

**Body**

```
New late check-in: {{1}} guests arriving around {{2}}. ₹{{3}} to collect at the desk, ₹{{4}} paid online. Booking code {{5}}.
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | Number of guests | `2` |
| `{{2}}` | Arrival time (India time) | `11:30 pm` |
| `{{3}}` | Amount to collect at the desk, en-IN digits (the ₹ is in the template) | `1,100` |
| `{{4}}` | Deposit already paid online | `200` |
| `{{5}}` | 4-digit code the guest will show at the desk | `4821` |

**Buttons:** two **Quick reply** buttons, in this order:

| # | Button text | Effect |
| --- | --- | --- |
| 1 | `Confirm` | Booking becomes HOTEL_CONFIRMED; the traveller's pass shows the code |
| 2 | `Can't take it` | Booking becomes HOTEL_DECLINED; the deposit is refunded in full |

If Meta rejects the body for having too many variables for its length, submit this longer wording instead (same variables, same order) and nothing in the code needs to change:

```
New late check-in request from LateStay: {{1}} guests arriving around {{2}} tonight. Please collect ₹{{3}} at the desk; ₹{{4}} has already been paid online. The guest will show booking code {{5}}. Please confirm within 5 minutes.
```

## 3. `booking_cancelled`

Sent when a booking the owner was asked about falls through before they confirmed it: the traveller cancelled, or the 5 minutes ran out.

**Body**

```
Booking {{1}} for tonight is cancelled ({{2}}). Please don't hold the room. Any deposit has been refunded to the guest.
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | The booking's 4-digit code | `4821` |
| `{{2}}` | Why | `the guest cancelled` or `it wasn't confirmed within 5 minutes` |

**Buttons:** none.

## Messages that need no template

These always answer a message the owner has just sent, so they fall inside the 24-hour customer service window and go out as plain text:

- Availability acknowledgements: "Got it: 2 rooms free tonight at Pine Crest. We'll message you here when a guest books."
- Booking answers: "Confirmed. 2 guests arriving around 11:30 pm. They'll show code 4821 at the desk; collect ₹1,100." / "Declined. The guest gets a full refund."
- Late or repeated taps: "This booking timed out after 5 minutes and the guest was refunded." / "Already confirmed."
- Help when a message isn't understood: "Sorry, I didn't get that. Tap a button on my last message, or send the number of rooms free tonight, like "rooms 2" or "2 room khali"."

## Checking a template before going live

Templates take minutes to a day to approve. Until they are approved, sends fail with Graph API error 132001 (template does not exist); after five attempts the admin panel's WhatsApp log shows the message as FAILED with that error. Meta's test phone number can send approved templates to up to five verified recipient numbers, which is enough to try the whole flow with your own phone as the "owner".
