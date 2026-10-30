# LateStay for iOS and Android

The apps are Capacitor 8 shells (`mobile/`) around the live web app, with native features added: push
notifications, passes saved for offline use, location, the share sheet, haptics, a native tab bar and Razorpay's
native checkout. How it fits together: [ARCHITECTURE.md](ARCHITECTURE.md#mobile-apps).

## How the app loads its content

- Each build loads one web app through `server.url`: production (`https://latestay.in`) or staging
  (`https://staging.latestay.in`). A web deploy updates the apps at once. Only native changes (plugins, permissions,
  icons, the offline page) need a store release.
- The main view may only show our hosts and Razorpay's checkout (`allowNavigation` in `mobile/app.config.mjs`).
  Everything else opens outside the app: maps, the dialler, WhatsApp, the browser. The policy pages open in the
  in-app browser.
- **No connection:**
  - On iOS, the bundled `mobile/www/offline.html` shows "No connection", the passes saved on the phone and Retry.
  - Android gives that bundled page no access to Capacitor's plugins, so it can't read the saved passes. There, the
    web app's service worker serves the app's own offline screen (`/~offline`), which lists them. The bundled page is
    the last resort (Retry only).
- **Two risks to know about:**
  - Capacitor's documentation marks `server.url` and `allowNavigation` as "not intended for production". The plugin
    bridge does work on the loaded site, and the apps depend on that.
  - Apple can reject apps that are mostly a website (App Store Review Guideline 4.2). The native features below are
    the answer to that, and the review notes say so.

## Settings and versions

| Setting | Where | Default |
| --- | --- | --- |
| Version | `mobile/package.json` `version`, the only place | `1.0.0` |
| Build number | derived: 1.2.3 → 10203 | |
| `APP_ENV` | env at build time | `production` (or `staging`) |
| `APP_ID` | env at build time (bundle ID / application ID) | `app.latestay` |
| `SERVER_URL` | env at build time | from `APP_ENV` |

`npm run sync` in `mobile/` runs `scripts/configure.mjs`, which writes the ID, version and build number into both
native projects and the offline page's settings, then `npx cap sync`. Use a different `APP_ID` for staging (for
example `app.latestay.staging`) so both apps can be installed side by side.

The web app needs `APPLE_TEAM_ID`, `ANDROID_CERT_SHA256`, `MOBILE_APP_ID` and `NEXT_PUBLIC_APP_HOSTS`, and the backend
needs `FIREBASE_SERVICE_ACCOUNT_JSON` ([DEPLOY.md](DEPLOY.md)).

## What you need

- **Accounts:** an Apple Developer Program membership, a Google Play Console account, and one Firebase project per
  environment (staging and production).
- **iOS tools:** a Mac with Xcode (the version current for the App Store) and CocoaPods. The iOS project uses
  CocoaPods because Razorpay's plugin ships only a podspec.
- **Android tools:** Android Studio (SDK 36) and JDK 21.
- **Node:** 22 or newer, then `cd mobile && npm ci`.

## Secrets: what lives where (never in git)

| File / value | Used by | Keep it in |
| --- | --- | --- |
| `GoogleService-Info.plist` (per environment) | iOS build, copied into the app when present | Password manager; copy to `mobile/ios/App/App/` before building |
| `google-services.json` (per environment) | Android build | Password manager; copy to `mobile/android/app/` before building |
| APNs authentication key (`.p8`) | Uploaded to Firebase (Project settings > Cloud Messaging > Apple app configuration) | Password manager |
| Android upload keystore (`.jks`) and its passwords | Signing the `.aab` | Password manager, plus an offline backup. Losing it means asking Google for an upload key reset |
| `android/keystore.properties` | Gradle signing (below) | Your machine only (git-ignored) |
| Firebase service account JSON | Backend `FIREBASE_SERVICE_ACCOUNT_JSON` | Fly secrets for each environment |

`mobile/.gitignore` refuses all of these.

## iOS: build, sign and upload to TestFlight

1. `cd mobile && APP_ENV=production npm run sync` (this runs `pod install`). Copy that environment's
   `GoogleService-Info.plist` into `ios/App/App/`.
2. `npx cap open ios`, select the **App** target, then under Signing & Capabilities pick your team. Keep automatic
   signing. Check that the capabilities show Push Notifications, Associated Domains (`applinks:latestay.in`,
   `applinks:staging.latestay.in`) and Background Modes > Remote notifications.
3. Product > Archive (destination "Any iOS Device"), then Distribute App > App Store Connect > Upload.
4. In App Store Connect, add the build to a TestFlight group and test it (the device test plan below) before
   submitting it for review.

## Android: build, sign and upload to Play closed testing

1. Create the upload key once, outside the repo:
   ```bash
   keytool -genkeypair -v -keystore ~/keys/latestay-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 9125
   ```
2. Create `mobile/android/keystore.properties` (git-ignored) with `storeFile=/Users/you/keys/latestay-upload.jks`,
   `storePassword=…`, `keyAlias=upload` and `keyPassword=…`. `app/build.gradle` signs release builds with it when
   the file exists; without it they're unsigned (as in CI), and you can sign in Android Studio instead (Build >
   Generate Signed App Bundle).
3. Copy that environment's `google-services.json` to `mobile/android/app/`, then run
   `APP_ENV=production npm run sync`.
4. Build: `cd android && ./gradlew bundleRelease`. The bundle is `app/build/outputs/bundle/release/app-release.aab`.
5. In the Play Console, turn on Play App Signing, upload the bundle to Testing > Closed testing, and add testers.
6. Copy the SHA-256 fingerprints of both the upload key and Play's app-signing key (Setup > App signing) into the
   web app's `ANDROID_CERT_SHA256`, so App Links verify.

CI (`.github/workflows/mobile.yml`) builds both platforms on every push without signing, so a broken native build
shows up straight away.

## Push notifications

- **Setup per environment:**
  - Create a Firebase project and add an iOS app and an Android app with the bundle / application ID.
  - Download the two config files (see Secrets).
  - Upload the APNs key under Project settings > Cloud Messaging.
  - Generate a service account key and set it as the backend's `FIREBASE_SERVICE_ACCOUNT_JSON`.
- **iOS tokens:** `AppDelegate.swift` gives the web app an FCM token rather than the raw APNs token, following
  Capacitor's "Push Notifications with Firebase" guide. Without the plist, the app runs with push off.
- **When we ask:** permission is asked only after the deposit is paid, behind our card ("Get a notification when the
  hotel confirms."), never at first launch, and once per install. Android 13 and later shows its own system prompt
  after that.
- **What gets sent:** confirmed ("Deodar Nest confirmed. Your code is ready."), declined, not confirmed in time,
  cancelled after payment, rescued, refunded, and the 11:00 review request. Tapping one opens that booking's pass.
  Notifications never contain a desk code, a phone number or a name, and there are no marketing notifications.

## Payments

- **Plugin:** the apps use Razorpay's official native plugin (`capacitor-razorpay`, from razorpay-cordova).
  - In a WebView, Checkout can't reliably open UPI apps and come back.
  - Razorpay's SDK opens the UPI app itself and returns to LateStay with the result.
  - The order, the signature check and the webhook are the same as on the website.
- **No in-app purchase:** the deposit is for a real-world service, a hotel stay, so it goes through Razorpay and not
  Apple's or Google's in-app purchase (App Store guideline 3.1.3(e), Google Play's payments policy for physical
  goods and services).
- **Before every release:** test both platforms with Razorpay's test card and test UPI ID, in test mode (staging).
- **Known risks with the plugin:**
  - Its Android code uses an older Capacitor annotation (`@NativePlugin`) and a `jcenter()` repository.
  - It is CocoaPods-only, and CocoaPods' central repository becomes read-only in December 2026 (existing pods keep
    installing).
  - If it ever stops building, the fallback is a small plugin of our own wrapping Razorpay's iOS and Android SDKs
    directly.

## Deep links

`https://latestay.in/pass/*` and `/stay/*` open in the app when it is installed, and in the browser otherwise.

- **iOS:** set the web app's `APPLE_TEAM_ID` (and `MOBILE_APP_ID` if it isn't `app.latestay`). Check that
  `https://latestay.in/.well-known/apple-app-site-association` returns JSON.
- **Android:** set `ANDROID_CERT_SHA256` and check `https://latestay.in/.well-known/assetlinks.json`. Then
  `adb shell pm verify-app-links --re-verify app.latestay` and `adb shell pm get-app-links app.latestay`.

## Permissions

| Permission | Platform | When it's asked | What the traveller sees |
| --- | --- | --- | --- |
| Location (when in use) | iOS `NSLocationWhenInUseUsageDescription`; Android `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | On tapping "I need a room now" (or "Use my location" in search) | iOS: "To find rooms free tonight near you." Android shows its standard dialog, which can't carry our text |
| Notifications | iOS push; Android 13+ `POST_NOTIFICATIONS` | After the deposit is paid, behind our card | Our card: "Get a notification when the hotel confirms." Then the system prompt |

No other permissions: no camera, contacts, tracking or background location.

## Privacy: Apple and Google answers

These must match /privacy (`web/src/app/privacy/page.tsx`) and the iOS privacy manifest
(`mobile/ios/App/App/PrivacyInfo.xcprivacy`). Change all of them together.

### Apple: App Privacy (App Store Connect)

- **Tracking:** No. There are no ads, no data brokers, and no cross-app tracking.
- **Data collected:**

  | Data type | Linked to the user | Used for tracking | Purposes |
  | --- | --- | --- | --- |
  | Contact Info: Name | Yes | No | App Functionality |
  | Contact Info: Phone Number | Yes | No | App Functionality |
  | Location: Precise Location | Yes | No | App Functionality |
  | Purchases: Purchase History | Yes | No | App Functionality |
  | User Content: Other User Content (reviews) | Yes | No | App Functionality |
  | Identifiers: Device ID (push token) | Yes | No | App Functionality |
  | Usage Data: Product Interaction | No | No | Analytics |

- Card and UPI details are collected by Razorpay's SDK, not by us. Razorpay's SDK ships its own privacy manifest.

### Google Play: Data safety

- **Data collected:**

  | Data type | Collected | Shared | Optional | Purpose |
  | --- | --- | --- | --- | --- |
  | Personal info: Name | Yes | Yes (with the hotel you book) | No | App functionality |
  | Personal info: Phone number | Yes | No | No | App functionality, fraud prevention |
  | Location: Approximate and precise | Yes | No | Yes | App functionality |
  | Financial info: Purchase history | Yes | No | No | App functionality |
  | App activity: Other user-generated content (reviews) | Yes | No | Yes | App functionality |
  | App activity: App interactions | Yes | No | No | Analytics |
  | Device or other IDs (push token) | Yes | No | Yes | App functionality |

- **Answers to the general questions:**
  - Data is encrypted in transit: Yes.
  - Users can request deletion: Yes, by emailing support (as /privacy says).
  - Ads: No. Independent security review: No.
- Payment card data is handled by Razorpay, a payment processor, and isn't counted as our collection.

## Store listing (drafts)

Nothing invented: no ratings, numbers of hotels or bookings, or claims we can't stand behind.

- **Name:** LateStay
- **Subtitle (iOS, 30 characters):** Late-night rooms in the hills
- **Short description (Play, 80 characters):** Find a room for the rest of the night, agree a price with the desk and pay a deposit.
- **Full description:**

  > Reached the hills late at night? LateStay shows hotels that still have rooms free tonight near you, in Manali, Old
  > Manali and Kasol.
  >
  > Pick a hotel and agree a price for the rest of the night in a chat with its front desk. The price only comes down
  > from where it starts. Pay a small deposit online (₹200, or the whole price if it's less) and pay the rest at the
  > desk. Once the hotel confirms, your booking pass shows a 4-digit code to show at reception.
  >
  > - Rooms free tonight, near you or in the town you pick
  > - Haggle in a chat; the hotel's lowest price is protected, and so is yours
  > - A notification when the hotel confirms, if you want one
  > - Your pass and code work without a connection
  > - Share your hotel and arrival time with family (never your code or the price)
  > - If the hotel turns you away, you get your deposit back and a list of places with rooms
  >
  > No account, no password. Cancel for a full refund until the hotel confirms. Payments are handled by Razorpay.

- **Keywords (iOS, 100 characters):** hotel,late night,room tonight,Manali,Kasol,check-in,homestay,haggle,last minute,hills
- **Category:** Travel. **Age rating:** 4+ / Everyone.
- **Support URL:** https://latestay.in/contact. **Privacy policy:** https://latestay.in/privacy.
- **Screenshots:** `cd web && npx playwright test store --project=390` writes `mobile/store/screenshots/` at iPhone
  6.9" (1320×2868), iPhone 6.5" (1284×2778) and Android phone (1080×1920). For the listing, take them from staging:
  build the web app against staging's API and set `STORE_PASS_ID` to a real confirmed booking there. The ones in the
  repo were taken against a local backend with seed hotels.

## App Review notes (Apple)

> LateStay helps travellers who reach Indian hill stations late at night find a hotel that still has a room, agree a
> price with the hotel's front desk in a chat, and pay a small deposit. The hotel then confirms and the traveller
> shows a 4-digit code at reception.
>
> To try it: open the app (no account needed). The Tonight tab searches Manali by default; partner hotels there
> report rooms between 9 pm and 3 am India time (arrivals 9:00 pm to 3:00 am). Choose a hotel, tap "Start
> haggling", then "Accept". You'll be asked for a phone number for a WhatsApp code before paying; please contact us
> at [support email] and we'll send the code to you during the review.
>
> Payments: the deposit is for a real-world service (a hotel stay), paid with Razorpay, not in-app purchase
> (guideline 3.1.3(e)).
>
> Native features: push notifications when the hotel confirms (asked only after payment), booking passes saved on
> the phone for use offline (Bookings tab, and a native offline screen), location for "I need a room now", the
> system share sheet for "Share with family", haptics, Universal Links for passes, and a native tab bar.

**Warning:** review needs real hotels visible for tonight on production at the time Apple looks, so partner hotels
must have answered that evening's availability ping. Never add fake hotels to production for review. Submit at a
time when the partners are active, and keep someone ready to send the WhatsApp verification code.

## Release checklist

1. Bump `version` in `mobile/package.json`; `npm run sync` applies it everywhere.
2. Check the web app and backend for this release are deployed (the apps load whatever production serves).
3. Build both platforms (iOS archive and a signed Android `.aab`) with production's Firebase files.
4. Run the device test plan below on a real iPhone and a real Android phone, from TestFlight and Play closed testing.
5. Update the store screenshots if the screens changed, and the privacy answers if the data changed (keep /privacy,
   `PrivacyInfo.xcprivacy` and this file the same).
6. Upload: App Store Connect (submit with the review notes above) and Play (promote from closed testing to
   production).

## Device test plan (M13)

Run on a real iPhone and a real Android phone, on staging (Razorpay test mode) unless noted.

1. **First launch:** splash (white, LateStay in ink) then Tonight with the tab bar. No permission prompt at launch.
2. **Safe areas:** nothing under the notch or the home indicator. The chat composer and Accept sit above the keyboard
   and the home indicator, and the tab bar hides while typing.
3. **Status bar:** dark text on white everywhere.
4. **I need a room now:** the location prompt shows "To find rooms free tonight near you." on iOS. Allowing it gives
   results near you; refusing it shows the town picker.
5. **Haggle:** a light tap on Accept.
6. **Payment by test card:** the pass shows "Waiting for the hotel".
7. **Payment by UPI (Android):** choose a UPI app, pay with Razorpay's test UPI ID, and check you return to
   LateStay's pass. Repeat on iOS where a UPI app is installed.
8. **Notifications:** the card appears after paying, then the system prompt. Confirm from /admin: a notification
   arrives with the app closed, tapping it opens the pass with the code, and with the app open the pass updates with
   a success buzz.
9. **Decline, cancel, rescue and refund:** each sends one notification (check nothing arrives twice).
10. **Offline:** in airplane mode, Bookings lists the pass with its code. Relaunching the app shows "No connection"
    with the saved passes (iOS) or the app's offline screen (Android), and Retry works once the connection is back.
11. **Links:**
    - Directions opens Apple Maps / the default maps app.
    - Call opens the dialler.
    - Support opens WhatsApp.
    - The refund policy and privacy pages open in the in-app browser.
12. **Share with family:** the system share sheet opens. The link opens in a browser on another phone and shows no
    code, phone number or price.
13. **Deep links:** tapping `https://latestay.in/pass/<id>` (production) or the staging equivalent in Notes or
    WhatsApp opens the app at that pass, and in a browser when the app isn't installed.
14. **Android back button:** it goes back through pages and exits only from Tonight.
15. **The review request:** at 11:00 after a completed stay, the notification opens the review form.
