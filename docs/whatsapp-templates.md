# WhatsApp message templates

LateStay talks to hotel owners (and, when they ask for it, travellers) through the WhatsApp Business Cloud API. A message LateStay starts (rather than one that answers someone within 24 hours of their last message) must use a template that Meta has approved. Submit these ten in **WhatsApp Manager > Message templates > Create template**, exactly as written: the backend sends them by name with this number of variables and buttons in this order (`backend/src/main/java/com/latestay/whatsapp/WhatsAppMessages.java`).

Templates 1 to 4 and 6 to 9:

- **Category:** Utility (they are about something the recipient signed up for: an owner's availability and bookings, a traveller's own booking, with no promotion). `phone_otp` (5) is Authentication.
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

## 4. `arrival_check`

Sent to the owner an hour after a confirmed guest's "arrive by" time. Arrived checks the guest in; Didn't arrive marks a no-show (the hotel keeps the deposit unless the traveller disputes it). If nobody answers by 10:00 the next morning (and at least 2 hours after this message), the booking completes on its own. The owner can also just type the guest's 4-digit code at any time.

- **Category:** Utility

**Body**

```
Did {{1}} (code {{2}}) arrive?
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | Guest's name | `Asha Verma` |
| `{{2}}` | 4-digit booking code | `4821` |

**Buttons:** two **Quick reply** buttons, in this order:

| # | Button text |
| --- | --- |
| 1 | `Arrived` |
| 2 | `Didn't arrive` |

If Meta rejects the body as too short for its variables, submit this instead (same variables, same order):

```
LateStay check-in: did your guest {{1}} (booking code {{2}}) arrive tonight? Please tap a button below.
```

## 5. `phone_otp`

Sent to the **traveller's** number before payment to verify it. Meta fixes the wording of authentication templates; you choose only the options below in WhatsApp Manager.

- **Category:** Authentication
- **Code delivery:** Copy code. Button text: `Copy code`
- **Add security recommendation:** on ("For your security, do not share this code.")
- **Add expiry time for the code:** on, **5** minutes ("This code expires in 5 minutes.")

What the traveller sees:

```
123456 is your verification code. For your security, do not share this code.
This code expires in 5 minutes.
[Copy code]
```

The backend sends the 6-digit code twice, in the body parameter and in the button parameter (`sub_type: "url"`, index 0), as Meta requires for copy-code buttons. The code is never stored or logged in plain text. Change `latestay.phone-verification.template` if you register it under another name.

## 6. `admin_alert`

Sent to the admin's own WhatsApp (`ADMIN_WHATSAPP_NUMBER`) when something needs a person now: a traveller says the hotel turned them away, or disputes a no-show. It's a template because the admin may not have messaged the business number in the last 24 hours.

- **Category:** Utility

**Body**

```
LateStay alert: {{1}}. Booking {{2}} at {{3}}. Please check the admin panel.
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | What happened | `guest turned away at the desk` or `guest says they did arrive` |
| `{{2}}` | 4-digit booking code | `4821` |
| `{{3}}` | Hotel name | `Pine Crest` |

**Buttons:** none.

## 7. `booking_confirmed`

Sent to the **traveller** when the hotel confirms (HOTEL_CONFIRMED), only if they ticked "Send my booking pass and updates on WhatsApp" at the phone step (`booking.whatsapp_updates_at`). It is their booking pass in a message.

- **Category:** Utility

**Body**

```
Your LateStay room is confirmed at {{1}}, {{2}}. Please arrive by {{3}} and pay ₹{{4}} at the desk. Show code {{5}} at reception. Your pass: {{6}} Need help? Message LateStay support: {{7}} Have a safe trip.
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | Hotel name | `Pine Crest` |
| `{{2}}` | Hotel address | `Mall Road, Manali` |
| `{{3}}` | Arrive-by time (India time) | `12:30 am` |
| `{{4}}` | Amount to pay at the desk, en-IN digits (the ₹ is in the template) | `1,100` |
| `{{5}}` | 4-digit desk code | `4821` |
| `{{6}}` | Booking pass link | `https://latestay.in/pass/0b6f3c5e-8c1a-4a57-9d55-1d0f6a2c3b4e` |
| `{{7}}` | Support link (`SUPPORT_WHATSAPP_NUMBER` as wa.me; `/contact` if unset) | `https://wa.me/919876543210` |

**Buttons:** none. The body ends with fixed text because Meta rejects templates that end with a variable.

## 8. `booking_update`

Sent to an opted-in **traveller** once for each of: the hotel declined, the hotel didn't confirm in 5 minutes, the traveller cancelled after paying, an admin Rescue (HOTEL_FAILED), and the refund going through (REFUNDED). Nothing for an unpaid cancel or expiry. Also once at 11:00 after a stay that ended checked in or completed (left in the last 24 hours, not yet rated), asking for a rating.

Meta may classify a message asking for feedback as Marketing rather than Utility (Marketing costs more per message, and Meta can recategorise an approved template). If that happens, either accept the Marketing rate, or stop `ReviewInvites` and let travellers rate from the pass only.

- **Category:** Utility

**Body**

```
LateStay booking update: {{1}}. Your pass has the details: {{2}} Thank you.
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | One short line, no full stop | `Pine Crest couldn't take your booking, so your ₹200 deposit is being refunded in full` |
| `{{2}}` | Booking pass link | `https://latestay.in/pass/0b6f3c5e-8c1a-4a57-9d55-1d0f6a2c3b4e` |

The lines `{{1}}` can be (`TravellerNotifications.updateLine`):

| Booking becomes | Line |
| --- | --- |
| HOTEL_DECLINED | `Pine Crest couldn't take your booking, so your ₹200 deposit is being refunded in full` |
| HOTEL_TIMEOUT | `Pine Crest didn't confirm in time, so your ₹200 deposit is being refunded in full` |
| CANCELLED (after payment) | `your booking at Pine Crest is cancelled and your ₹200 deposit is being refunded` |
| HOTEL_FAILED | `we're sorry Pine Crest let you down; your ₹200 is being refunded and your pass lists nearby hotels with rooms` |
| REFUNDED | `your ₹200 refund has been processed` |
| The stay ended (11:00 the next morning, `ReviewInvites`) | `how was your stay at Pine Crest? You can rate it in a few taps from your pass` (`{{2}}` is then the pass's review page, `/pass/<id>/review`) |

**Buttons:** none.

Messages to travellers are stored with `audience = TRAVELLER`; their number and the message body are redacted 90 days after the stay, with the booking's phone number.

## 2b. `booking_request_rooms`

As `booking_request`, for a group's booking of two rooms at once (no single room sleeps them all). Same buttons and payloads, same 5 minutes. Until it is approved, set nothing: two-room bookings go out as `booking_request`, and the owner page and the arrival check still say "2 rooms". Once approved, set `WA_ROOMS_TEMPLATE=true`.

- **Category:** Utility

**Body**

```
New late check-in: {{1}} guests in {{2}} rooms arriving around {{3}}. ₹{{4}} to collect at the desk, ₹{{5}} paid online. Booking code {{6}}.
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | Number of guests | `5` |
| `{{2}}` | Number of rooms | `2` |
| `{{3}}` | Arrival time (India time) | `11:30 pm` |
| `{{4}}` | Amount to collect at the desk for both rooms | `2,400` |
| `{{5}}` | Deposit already paid online | `200` |
| `{{6}}` | 4-digit code the guest will show at the desk | `4821` |

**Buttons:** the same two **Quick reply** buttons as `booking_request`: `Confirm`, then `Can't take it`.

For two-room bookings, `arrival_check` sends the guest's name as "Asha Verma, 2 rooms" and `booking_cancelled` adds ", 2 rooms" to the reason; no change to those templates.

## 9. `morning_digest`

Sent to the admin's own WhatsApp (`ADMIN_WHATSAPP_NUMBER`) every day at 09:00 Asia/Kolkata: the night that just
ended (`night_of` = yesterday). The same numbers are on `/admin/today`.

- **Category:** Utility

**Body**

```
LateStay, night of {{1}}: {{2}} bookings, {{3}} checked in, {{4}} completed, {{5}} no-shows, {{6}} rescues. Open disputes: {{7}}. Refunds: {{8}} (₹{{9}}). Your commission ₹{{10}}; owed to hotels ₹{{11}}. No answer to the ping: {{12}}. LLM cost ₹{{13}}. Details in the admin panel.
```

| Variable | Meaning | Sample |
| --- | --- | --- |
| `{{1}}` | The night | `Wed 11 Nov` |
| `{{2}}` | Paid bookings for that night | `6` |
| `{{3}}` | Of those, checked in | `3` |
| `{{4}}` | Completed | `1` |
| `{{5}}` | No-shows | `1` |
| `{{6}}` | Rescues (HOTEL_FAILED) | `1` |
| `{{7}}` | Bookings in dispute now, any night | `2` |
| `{{8}}` | Refunds processed 09:00 yesterday to 09:00 today | `2` |
| `{{9}}` | Their total, en-IN (the ₹ is in the template) | `400` |
| `{{10}}` | LateStay's commission on that night's bookings, net of promo codes, none on refunded ones | `250` |
| `{{11}}` | Everything not yet paid out to hotels | `1,275` |
| `{{12}}` | Active hotels that didn't answer that night's ping: up to three names, then "+N more"; or `none` | `Apple Stay, Deodar Nest, Moss Rooms +1 more` |
| `{{13}}` | LLM cost 09:00 yesterday to 09:00 today, in rupees at `USD_INR_RATE` | `132` |

**Buttons:** none. Queued once per night (dedupe key `morning_digest:<night>`); with no `ADMIN_WHATSAPP_NUMBER` it
is only logged.

## Messages that need no template

These always answer a message the owner has just sent, so they fall inside the 24-hour customer service window and go out as plain text:

- Availability acknowledgements: "Got it: 2 rooms free tonight at Pine Crest. We'll message you here when a guest books."
- Booking answers: "Confirmed. 2 guests arriving around 11:30 pm. They'll show code 4821 at the desk; collect ₹1,100." / "Declined. The guest gets a full refund."
- Late or repeated taps: "This booking timed out after 5 minutes and the guest was refunded." / "Already confirmed."
- Arrival answers and typed codes: "Thanks. Asha Verma is checked in." / "Code 4821 is right: Asha Verma, 2 guests. Collect ₹1,100. Checked in."
- Help when a message isn't understood: "Sorry, I didn't get that. Tap a button on my last message, or send the number of rooms free tonight, like "rooms 2" or "2 room khali"." This also answers 4 digits that aren't the code of an active booking (confirmed, or marked didn't arrive) at the owner's hotel: 4 digits are a desk code only when they match one.
- A room count above 50 ("120", "rooms 75"): "Send a number from 0 to 50 for the rooms free tonight, like "rooms 2"." Numbers of 1 to 3 digits are always read as room counts.
- The owner page link, when the owner sends "hisaab", "hisab", "account" or "earnings": "Your LateStay account for Pine Crest: https://latestay.in/owner/login?t=… The link works once, for the next 15 minutes. Don't forward it: it opens your bookings and earnings." One link per hotel on the number. Once WhatsApp accepts the message, the stored copy keeps the link with the token replaced by `[redacted]`.

## Checking a template before going live

Templates take minutes to a day to approve. Until they are approved, sends fail with Graph API error 132001 (template does not exist); after five attempts the admin panel's WhatsApp log shows the message as FAILED with that error. Meta's test phone number can send approved templates to up to five verified recipient numbers, which is enough to try the whole flow with your own phone as the "owner".
