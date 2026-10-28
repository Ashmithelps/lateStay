# Design brief

The web app borrows Airbnb's layout language (white canvas, big rounded photos, a floating pill search bar, quiet grey text and one warm accent) but not its logo, name or brand colour. M0 saves this brief in the repo as docs/DESIGN.md, and every frontend prompt points back to it.

| Token | Value | Use |
| --- | --- | --- |
| Canvas | #FFFFFF | Page background |
| Soft surface | #F7F7F7 | Chips, inputs, traveller chat messages |
| Ink | #222222 | Headings and body text |
| Ink secondary | #6A6A6A | Distances, metadata, helper text |
| Hairline | #DDDDDD | Borders and dividers |
| Ember (accent) | #D9572B, hover #BF4A22 | The one primary button per screen |
| Pine | #1E6B52 | Rooms free, confirmed |
| Error | #B42318 | Errors only |

**Type.** Plus Jakarta Sans via next/font, weights 400, 500 and 600. Sizes 32 / 26 / 22 / 18 / 16 / 14 / 12 px. Prices use tabular numbers and Intl.NumberFormat('en-IN') with ₹. Times read like "11:30 pm".

**Shape and depth.** 12 px radius on cards and photos, 8 px on buttons and inputs, full pill for the search bar and chips. Cards have no border and no shadow; the photo carries the card. Shadow 0 6px 16px rgba(0,0,0,0.12) only on floating things: the search pill, the mobile bottom bar and the map toggle.

## Key components

- **Search pill:** segments for Where, Arrive, Leave and Guests, with a round ember search button at the end.
- **Filter chips:** Heater, Parking, Couple-friendly, Near bus stand, Late check-in. Outline icons, horizontal scroll on mobile.
- **Hotel card:** 20:19 photo with a heart icon top-right; name with rating on the right; distance in grey; "2 rooms free tonight" in pine; "from ₹900 tonight" in 600 weight.
- **Hotel page:** one large photo plus four small on desktop, a swipe carousel on mobile; amenities with icons; a sticky haggle card on the right on desktop, a sticky bottom bar on mobile (price left, "Start haggling" right).
- **Haggle chat:** AI messages left-aligned as plain text next to a small hotel avatar; traveller messages in soft-grey pills on the right; the current offer pinned at the top; quick-offer chips; a full-width ember Accept button.
- **Booking pass:** a large 4-digit code, hairline dividers between rows, outline Directions and Call buttons.
- **Map toggle:** a floating ink-coloured pill, "Show map", bottom centre.
- **Icons:** lucide-react, stroke 1.5, 20–24 px. Motion 150–200 ms ease-out; skeleton loaders for photos.

Banned, because it reads as AI-generated: purple, indigo or violet anywhere; gradients; glassmorphism and blur; glows; sparkle or "magic" icons; emoji in the UI; a centred hero with a giant gradient headline; unstyled default shadcn components; cards inside cards; more than one ember button on a screen.
