import { readFileSync, writeFileSync } from "node:fs";
import { expect, test, type APIRequestContext, type Page, type TestInfo } from "@playwright/test";

/** Waits for the network to go quiet; pages that poll (the pass, admin) never fully do, so this is capped. */
async function quiet(page: Page) {
  await page.waitForLoadState("networkidle", { timeout: 5_000 }).catch(() => {});
}

/** Waits for the network and for every image currently on screen. */
async function settle(page: Page) {
  await quiet(page);
  await page.waitForFunction(
    () =>
      Array.from(document.images)
        .filter((img) => {
          const r = img.getBoundingClientRect();
          return r.width > 0 && r.bottom > 0 && r.top < window.innerHeight;
        })
        .every((img) => img.complete),
    null,
    { timeout: 30_000 },
  );
  await page.waitForTimeout(300);
}

/** Scrolls to the bottom and back so lazy images load before a full-page shot. */
async function loadWholePage(page: Page) {
  const height = await page.evaluate(() => document.body.scrollHeight);
  for (let y = 0; y < height; y += 600) {
    await page.evaluate((top) => window.scrollTo(0, top), y);
    await page.waitForTimeout(150);
  }
  await page.evaluate(() => window.scrollTo(0, 0));
  await quiet(page);
}

async function shot(page: Page, info: TestInfo, name: string, fullPage = false) {
  if (fullPage) await loadWholePage(page);
  await settle(page);
  // Fixed bars would be stitched into the middle of a full-page capture; viewport shots show them in place.
  await page.screenshot({
    path: `screenshots/${info.project.name}-${name}.png`,
    fullPage,
    style: fullPage ? "[data-fixed] { visibility: hidden !important; }" : undefined,
  });
}

async function openFirstHotel(page: Page) {
  await page.goto("/");
  await page.locator("article a").first().click();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
}

async function startHaggling(page: Page) {
  await openFirstHotel(page);
  await page.getByRole("button", { name: "Start haggling" }).filter({ visible: true }).click();
  await page.waitForURL(/\/haggle\//);
  await expect(page.getByRole("button", { name: /^Accept/ })).toBeEnabled();
}

async function send(page: Page, text: string) {
  const before = await page.locator("ol > li").count();
  await page.getByLabel("Message the front desk").fill(text);
  await page.getByRole("button", { name: "Send" }).click();
  await expect(page.locator("ol > li")).toHaveCount(before + 2, { timeout: 30_000 });
  await expect(page.getByRole("status", { name: /typing/ })).toHaveCount(0);
}

test("home", async ({ page }, info) => {
  await page.goto("/");
  await expect(page.locator("article").first()).toBeVisible();
  await shot(page, info, "01-home");
  await shot(page, info, "01-home-full", true);
});

test("search sheet", async ({ page }, info) => {
  await page.goto("/");
  await expect(page.locator("article").first()).toBeVisible();
  const opener = info.project.name === "390" ? page.getByRole("button", { name: /^Search: / }) : page.getByRole("button", { name: /Where/ });
  await opener.click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await shot(page, info, "02-search-sheet");
});

test("empty state", async ({ page }, info) => {
  await page.goto("/?place=kasol&arrive=23:30&leave=09:00&guests=2&filters=parking,near_bus_stand");
  await expect(page.getByText("No rooms free nearby right now")).toBeVisible();
  await shot(page, info, "03-empty");
});

test("map", async ({ page }, info) => {
  await page.goto("/?view=map");
  await expect(page.locator(".price-pin").first()).toBeVisible();
  await page.waitForFunction(() => document.querySelectorAll("img.leaflet-tile-loaded").length >= 4, null, { timeout: 30_000 });
  await page.locator(".price-pin").nth(1).click();
  await expect(page.locator('.price-pin[data-selected="true"]')).toHaveCount(1);
  await shot(page, info, "04-map");
});

test("hotel page", async ({ page }, info) => {
  await openFirstHotel(page);
  await shot(page, info, "05-hotel");
  await shot(page, info, "05-hotel-full", true);
});

test("haggle", async ({ page }, info) => {
  await startHaggling(page);
  await page.getByRole("group", { name: "Quick offers" }).getByRole("button").first().click();
  await expect(page.locator("ol > li")).toHaveCount(4, { timeout: 30_000 });
  await expect(page.getByRole("status", { name: /typing/ })).toHaveCount(0);
  await send(page, "heater hai?");
  await shot(page, info, "06-haggle");

  await page.route("**/messages", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 3000));
    await route.continue();
  });
  await page.getByLabel("Message the front desk").fill("Can you do ₹600?");
  await page.getByRole("button", { name: "Send" }).click();
  await expect(page.getByRole("status", { name: /typing/ })).toBeVisible();
  await page.screenshot({ path: `screenshots/${info.project.name}-07-haggle-typing.png` });
  await expect(page.getByRole("status", { name: /typing/ })).toHaveCount(0, { timeout: 30_000 });
  await page.unroute("**/messages");

  for (let i = 0; i < 6 && (await page.getByText("Final offer", { exact: true }).count()) === 0; i++) {
    await send(page, "Can you do ₹200?");
  }
  await expect(page.getByText("Final offer", { exact: true })).toBeVisible();
  await shot(page, info, "08-haggle-final");

  await send(page, "₹200 last?");
  await expect(page.getByRole("link", { name: "See other rooms" })).toBeVisible();
  await shot(page, info, "09-haggle-no-deal");
});

const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const ADMIN = { username: process.env.ADMIN_USERNAME ?? "admin", password: process.env.ADMIN_PASSWORD ?? "" };
const adminHeaders = {
  Authorization: `Basic ${Buffer.from(`${ADMIN.username}:${ADMIN.password}`).toString("base64")}`,
  "Content-Type": "application/json",
};

/** Every seeded hotel gets rooms for tonight, so repeated bookings don't run the first one dry. */
test.beforeAll(async ({ request }) => {
  const tonight = await (await request.get(`${API}/api/admin/availability`, { headers: adminHeaders })).json();
  for (const hotel of tonight.hotels) {
    if (hotel.active) {
      await request.put(`${API}/api/admin/hotels/${hotel.hotelId}/availability/${tonight.nightOf}`, {
        headers: adminHeaders,
        data: { roomsFree: 9 },
      });
    }
  }
});

async function agree(page: Page) {
  await startHaggling(page);
  await page.getByRole("button", { name: /^Accept/ }).click();
  await page.waitForURL(/\/book\//);
  await expect(page.getByRole("button", { name: "Send code on WhatsApp" })).toBeVisible();
}

/** A different number each time, so the per-number code limit never trips across the run. */
function freshPhone(): string {
  return `9${String(Math.floor(Math.random() * 1e9)).padStart(9, "0")}`;
}

/** Name, number and ID, then the WhatsApp code (read from the local backend's dev endpoint). */
async function verifyPhone(page: Page, phone = freshPhone()) {
  await page.getByLabel("Name").fill("Asha Verma");
  await page.getByLabel("Mobile number (WhatsApp)").fill(phone);
  await page.getByLabel(/photo ID/).check();
  await page.getByRole("button", { name: "Send code on WhatsApp" }).click();
  await expect(page.getByLabel("6-digit code from WhatsApp")).toBeVisible();
  const { code } = await (await page.request.get(`${API}/api/dev/phone-verifications/latest?phone=${phone}`)).json();
  await page.getByLabel("6-digit code from WhatsApp").fill(code);
  await page.getByRole("button", { name: "Verify number" }).click();
  await expect(page.getByRole("button", { name: /^Pay / })).toBeVisible();
}

/** Books and pays (the local backend's fake gateway stands in for Razorpay Checkout); ends on the pass. */
async function bookAndPay(page: Page): Promise<string> {
  await agree(page);
  await verifyPhone(page);
  await page.getByRole("button", { name: /^Pay / }).click();
  await page.waitForURL(/\/pass\//);
  await expect(page.getByRole("heading", { name: /Waiting for/ })).toBeVisible();
  return page.url().split("/pass/")[1];
}

async function confirmed(page: Page, request: APIRequestContext): Promise<string> {
  const id = await bookAndPay(page);
  expect((await request.post(`${API}/api/admin/bookings/${id}/confirm`, { headers: adminHeaders })).ok()).toBe(true);
  await page.reload();
  await expect(page.getByText("Show this code at the desk")).toBeVisible();
  return id;
}

/** The owner answers on WhatsApp (local dev endpoint plays the owner). */
async function ownerTaps(request: APIRequestContext, bookingId: string, payload: string) {
  const booking = await (await request.get(`${API}/api/bookings/${bookingId}`)).json();
  await request.post(`${API}/api/dev/whatsapp/inbound`, {
    data: { from: booking.hotel.phone, buttonPayload: payload, buttonText: "Didn't arrive" },
  });
}

test("book", async ({ page }, info) => {
  await agree(page);
  await shot(page, info, "10-book");
  await shot(page, info, "10-book-full", true);
  await page.getByLabel("Mobile number (WhatsApp)").fill("12345");
  await page.getByRole("button", { name: "Send code on WhatsApp" }).click();
  await expect(page.getByText("Enter a 10-digit Indian mobile number.")).toBeVisible();
  await shot(page, info, "11-book-errors", true);

  const phone = freshPhone();
  await page.getByLabel("Name").fill("Asha Verma");
  await page.getByLabel("Mobile number (WhatsApp)").fill(phone);
  await page.getByLabel(/photo ID/).check();
  await page.getByRole("button", { name: "Send code on WhatsApp" }).click();
  await expect(page.getByLabel("6-digit code from WhatsApp")).toBeVisible();
  await page.getByLabel("6-digit code from WhatsApp").fill("000000");
  await page.getByRole("button", { name: "Verify number" }).click();
  await expect(page.getByText(/That code isn't right/)).toBeVisible();
  await shot(page, info, "26-book-code", true);

  const { code } = await (await page.request.get(`${API}/api/dev/phone-verifications/latest?phone=${phone}`)).json();
  await page.getByLabel("6-digit code from WhatsApp").fill(code);
  await page.getByRole("button", { name: "Verify number" }).click();
  await expect(page.getByRole("button", { name: /^Pay / })).toBeVisible();
  await expect(page.getByText("Verified on this phone")).toBeVisible();
  await shot(page, info, "27-book-verified", true);
});

test("pass: waiting", async ({ page }, info) => {
  await bookAndPay(page);
  await shot(page, info, "12-pass-waiting", true);
});

test("pass: confirmed", async ({ page, request }, info) => {
  await confirmed(page, request);
  await expect(page.getByText(/deposit is non-refundable/)).toBeVisible();
  await shot(page, info, "13-pass-confirmed", true);
  await page.goto("/");
  await expect(page.getByRole("link", { name: "Your booking" }).filter({ visible: true })).toBeVisible();
  await shot(page, info, "14-home-your-booking");
});

test("pass: no-show and I did arrive", async ({ page, request }, info) => {
  const id = await confirmed(page, request);
  await ownerTaps(request, id, `ARRIVAL|${id}|NOT_ARRIVED`);
  await page.reload();
  await expect(page.getByRole("heading", { name: /says you didn't arrive/ })).toBeVisible();
  await shot(page, info, "28-pass-no-show", true);
  await page.getByRole("button", { name: "I did arrive" }).click();
  await page.getByRole("button", { name: "Yes, I arrived" }).click();
  await expect(page.getByRole("heading", { name: "We're checking with the hotel" })).toBeVisible();
  await shot(page, info, "29-pass-disputed", true);
});

test("haggle: room gone", async ({ page }, info) => {
  await startHaggling(page);
  await page.route("**/api/negotiations/*/accept", (route) =>
    route.fulfill({
      status: 409,
      headers: { "Content-Type": "application/problem+json" },
      body: JSON.stringify({ status: 409, code: "ROOM_GONE", detail: "Sorry, the last room at Deodar Nest just went." }),
    }),
  );
  await page.getByRole("button", { name: /^Accept/ }).click();
  await expect(page.getByText("Sorry, the last room at Deodar Nest just went.")).toBeVisible();
  await shot(page, info, "30-haggle-room-gone");
});

test("refund policy and contact", async ({ page }, info) => {
  await page.goto("/refund-policy");
  await expect(page.getByRole("heading", { name: "Cancellation and refund policy", level: 1 })).toBeVisible();
  await shot(page, info, "31-refund-policy", true);
  await page.goto("/contact");
  await expect(page.getByRole("heading", { name: "Contact", level: 1 })).toBeVisible();
  await shot(page, info, "32-contact", true);
});

/** @night: run against a backend started with latestay.dev.clock-at at 23:45, after the 23:30 arrival. */
test("@night pass: turned away, then rescued", async ({ page, request }, info) => {
  const id = await confirmed(page, request);
  await expect(page.getByRole("button", { name: "The hotel turned me away" })).toBeVisible();
  await shot(page, info, "33-pass-arrived-help", true);
  await page.getByRole("button", { name: "The hotel turned me away" }).click();
  await page.getByRole("button", { name: "Yes, they turned me away" }).click();
  await expect(page.getByRole("heading", { name: /We're sorting out/ })).toBeVisible();
  await shot(page, info, "34-pass-turned-away", true);

  expect((await request.post(`${API}/api/admin/bookings/${id}/rescue`, { headers: adminHeaders })).ok()).toBe(true);
  await page.reload();
  await expect(page.getByRole("heading", { name: /let you down/ })).toBeVisible();
  await expect(page.getByText("Rooms free nearby right now")).toBeVisible();
  await shot(page, info, "35-pass-rescued", true);
});

test("pass: declined and refunded", async ({ page, request }, info) => {
  const id = await bookAndPay(page);
  await request.post(`${API}/api/admin/bookings/${id}/decline`, { headers: adminHeaders });
  // The refund job runs every 15 s; the pass polls every 5 s.
  await expect(page.getByText(/has been refunded/)).toBeVisible({ timeout: 60_000 });
  await shot(page, info, "15-pass-refunded", true);
});

async function signIn(page: Page) {
  await page.goto("/admin");
  await page.getByLabel("Username").fill(ADMIN.username);
  await page.getByLabel("Password").fill(ADMIN.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

test("admin", async ({ page }, info) => {
  await page.goto("/admin");
  await expect(page.getByRole("heading", { name: "Admin sign-in" })).toBeVisible();
  await shot(page, info, "16-admin-sign-in");

  // Something to confirm on the board, a dispute, and a live negotiation to read.
  await bookAndPay(page);
  const disputed = await confirmed(page, page.request);
  await ownerTaps(page.request, disputed, `ARRIVAL|${disputed}|NOT_ARRIVED`);
  await page.request.post(`${API}/api/bookings/${disputed}/arrived`);
  await startHaggling(page);
  await send(page, "700 mein de do");

  await signIn(page);
  await expect(page.getByRole("button", { name: "Confirm" }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Guest stayed" }).first()).toBeVisible();
  await shot(page, info, "17-admin-bookings", true);
  await page.getByRole("button", { name: "Rescue" }).first().click();
  await expect(page.getByRole("button", { name: "Confirm rescue" })).toBeVisible();
  await shot(page, info, "17b-admin-rescue-confirm");

  await page.getByRole("button", { name: "Negotiations" }).click();
  await expect(page.getByText(/engine /).first()).toBeVisible();
  await shot(page, info, "18-admin-negotiations", true);

  await page.getByRole("button", { name: "Hotels", exact: true }).click();
  await expect(page.getByRole("button", { name: "Save hotel" })).toBeVisible();
  // A WhatsApp exchange to show in the log: a ping, then the owner typing an answer (local dev endpoint).
  await page.getByRole("button", { name: "Send availability ping now" }).click();
  await expect(page.getByText("Ping queued")).toBeVisible();
  const owner = (await page.getByText(/^Owner number /).textContent())!.replace("Owner number ", "");
  await page.request.post(`${API}/api/dev/whatsapp/inbound`, { data: { from: owner, text: "2 room khali hai" } });
  await expect(page.getByText("2 room khali hai").first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/Floor rules/).first()).toBeVisible();
  await shot(page, info, "19-admin-hotels");
  await shot(page, info, "19-admin-hotels-full", true);
});

test("privacy and terms", async ({ page }, info) => {
  await page.goto("/privacy");
  await expect(page.getByRole("heading", { name: "Privacy", level: 1 })).toBeVisible();
  await expect(page.getByText(/phone number is deleted 90 days after your stay/i)).toBeVisible();
  await shot(page, info, "20-privacy", true);
  await page.goto("/terms");
  await expect(page.getByRole("heading", { name: "Terms", level: 1 })).toBeVisible();
  await shot(page, info, "21-terms", true);
});

test("haggle: rate limited", async ({ page }, info) => {
  await startHaggling(page);
  await page.route("**/api/negotiations/*/messages", (route) =>
    route.fulfill({
      status: 429,
      headers: { "Retry-After": "12", "Content-Type": "application/problem+json" },
      body: JSON.stringify({ status: 429, detail: "Too many requests. Try again in 12 seconds.", retryAfter: 12 }),
    }),
  );
  await page.getByLabel("Message the front desk").fill("700 mein de do");
  await page.getByRole("button", { name: "Send" }).click();
  await expect(page.getByText(/Slow down a little/)).toBeVisible();
  await shot(page, info, "22-haggle-rate-limited");
});

test("offline: saved pass, last search and the offline page", async ({ page, context, request }, info) => {
  const id = await bookAndPay(page);
  expect((await request.post(`${API}/api/admin/bookings/${id}/confirm`, { headers: adminHeaders })).ok()).toBe(true);

  // Let the service worker install and take control, then load the pages once so they're cached.
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await expect(page.getByText("Show this code at the desk")).toBeVisible();
  await page.goto("/");
  await expect(page.locator("article").first()).toBeVisible();
  await page.goto(`/pass/${id}`);
  await expect(page.getByText("Show this code at the desk")).toBeVisible();

  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText("Show this code at the desk")).toBeVisible();
  await expect(page.getByText(/You're offline\. This pass was last updated/)).toBeVisible();
  await shot(page, info, "23-pass-offline");

  await page.goto("/");
  await expect(page.locator("article").first()).toBeVisible();
  await expect(page.getByText(/You're offline\. Showing your last search/)).toBeVisible();
  await shot(page, info, "24-home-offline");

  await page.goto("/hotels/not-visited-yet");
  await expect(page.getByRole("heading", { name: "You're offline" })).toBeVisible();
  await shot(page, info, "25-offline-page");
  await context.setOffline(false);
});

/*
 * Owner page, room now, the WhatsApp opt-in and the admin photo manager. Run in two passes against a backend with
 * latestay.dev.clock-at, and the browser clock set to match (SHOT_CLOCK):
 *   @owner-history  clock 2026-11-01T23:40 (Sunday): a guest checks in, so last week has earnings;
 *   @v7             clock 2026-11-02T23:35 (Monday night): settle and pay last week, tonight's guests, then the pages.
 * Photo uploads need the imaging service and an S3 bucket behind the backend, and SHOT_PHOTOS (a folder of JPEGs
 * plus a .HEIC).
 */
async function atShotClock(page: Page, at = process.env.SHOT_CLOCK) {
  if (at) await page.clock.install({ time: new Date(`${at}:00+05:30`) });
}

async function checkedIn(page: Page, request: APIRequestContext): Promise<string> {
  const id = await confirmed(page, request);
  const booking = await (await request.get(`${API}/api/bookings/${id}`)).json();
  await request.post(`${API}/api/dev/whatsapp/inbound`, { data: { from: booking.hotel.phone, text: booking.code } });
  return id;
}

async function hotelOf(request: APIRequestContext, bookingId: string): Promise<{ id: string; slug: string }> {
  const booking = await (await request.get(`${API}/api/bookings/${bookingId}`)).json();
  return { id: booking.hotel.id, slug: booking.hotel.slug };
}

test("@owner-history a guest checks in last week", async ({ page, request }) => {
  await atShotClock(page);
  await checkedIn(page, request);
});

test("@v7 book: WhatsApp opt-in at the phone step", async ({ page }, info) => {
  await atShotClock(page);
  await agree(page);
  await page.getByLabel("Name").fill("Asha Verma");
  await page.getByLabel("Mobile number (WhatsApp)").fill(freshPhone());
  await expect(page.getByLabel(/Send my booking pass and updates on WhatsApp/)).not.toBeChecked();
  await page.getByLabel(/Send my booking pass and updates on WhatsApp/).check();
  await page.getByLabel(/photo ID/).check();
  await page.getByRole("button", { name: "Send code on WhatsApp" }).click();
  await expect(page.getByLabel("6-digit code from WhatsApp")).toBeVisible();
  await shot(page, info, "38-book-opt-in", true);
});

test("@v7 owner page", async ({ page, request, browser }, info) => {
  await atShotClock(page);
  // Last week's settlement (from the @owner-history pass), paid by UPI.
  await request.post(`${API}/api/admin/settlements/run`, { headers: adminHeaders });
  const here = await checkedIn(page, request);
  const hotel = await hotelOf(request, here);
  const due = await (await request.get(`${API}/api/admin/settlements?status=DUE`, { headers: adminHeaders })).json();
  for (const s of due.filter((s: { hotelId: string }) => s.hotelId === hotel.id)) {
    await request.post(`${API}/api/admin/settlements/${s.id}/paid`, { headers: adminHeaders, data: { upiReference: "431298765012" } });
  }
  await confirmed(page, request);

  const { url } = await (await request.post(`${API}/api/dev/owner-links?hotel=${hotel.slug}`)).json();
  await page.goto(url.replace(/^https?:\/\/[^/]+/, ""));
  await page.waitForURL(/\/owner$/);
  await expect(page.getByRole("heading", { name: "Tonight's guests" })).toBeVisible();
  await expect(page.getByText("Checked in").first()).toBeVisible();
  await shot(page, info, "39-owner", true);

  const price = page.getByRole("textbox").first();
  await price.fill("99999");
  await expect(page.getByText(/Can't be above the list price/)).toBeVisible();
  await page.getByText(/Can't be above the list price/).evaluate((el) => el.scrollIntoView({ block: "center" }));
  await shot(page, info, "40-owner-floor-error");

  // A used link, and no cookie: how to get a new one.
  const other = await browser.newPage({ viewport: page.viewportSize()! });
  await other.goto(url.replace(/^https?:\/\/[^/]+/, ""));
  await expect(other.getByRole("heading", { name: "This link has expired" })).toBeVisible();
  await other.screenshot({ path: `screenshots/${info.project.name}-41-owner-link-expired.png` });
  await other.close();
});

test("@v7 room now: located", async ({ page, context }, info) => {
  await context.grantPermissions(["geolocation"]);
  await context.setGeolocation({ latitude: 32.2432, longitude: 77.1892 });
  await atShotClock(page, "2026-11-02T23:40");
  await page.goto("/");
  await expect(page.locator("article").first()).toBeVisible();
  await shot(page, info, "36a-home-room-now-button");
  await page.getByRole("button", { name: "I need a room now" }).click();
  await page.waitForURL(/now=1/);
  await page.mouse.move(0, 0);
  await expect(page.getByRole("heading", { name: "Within 3 km" })).toBeVisible();
  await expect(page.getByText(/Arrive 11:45 pm · leave 9:00 am · 2 guests/)).toBeVisible();
  await shot(page, info, "36-room-now");
  await shot(page, info, "36-room-now-full", true);
});

test("@v7 room now: location denied", async ({ page, context }, info) => {
  await context.clearPermissions();
  await atShotClock(page, "2026-11-03T05:10");
  await page.goto("/");
  await expect(page.locator("article").first()).toBeVisible();
  await page.getByRole("button", { name: "I need a room now" }).click();
  await expect(page.getByText(/We couldn't get your location. Pick a town: arriving 5:15\sam, 2 guests./)).toBeVisible();
  await expect(page.getByLabel("Arrive")).toHaveValue("05:15");
  await expect(page.getByLabel("Leave")).toHaveValue("13:00");
  await shot(page, info, "37-room-now-denied");
});

test("@v7 admin photo manager", async ({ page, request }, info) => {
  const dir = process.env.SHOT_PHOTOS;
  test.skip(!dir, "SHOT_PHOTOS not set");
  await signIn(page);
  await page.getByRole("button", { name: "Hotels", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Photos" })).toBeVisible();
  const hotelId = (await (await request.get(`${API}/api/admin/hotels`, { headers: adminHeaders })).json())[0].id;
  const before: { id: number }[] = await (await request.get(`${API}/api/admin/hotels/${hotelId}/photos`, { headers: adminHeaders })).json();
  try {
    await page.locator('input[type="file"]').setInputFiles([`${dir}/room-1.jpg`, `${dir}/room-2.jpg`, `${dir}/room-3.jpg`, `${dir}/IMG_2041.HEIC`]);
    await expect(page.getByText(/IMG_2041.HEIC:.*HEIC photos aren't supported/)).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole("listitem").filter({ has: page.getByRole("button", { name: "Make cover" }) })).toHaveCount(before.length + 3);
    // The newest photo becomes the cover.
    await page.getByRole("button", { name: "Make cover" }).last().click();
    await expect(page.getByText("Cover").first()).toBeVisible();
    await page.getByText(/IMG_2041.HEIC:/).evaluate((el) => el.scrollIntoView({ block: "start" }));
    await page.evaluate(() => window.scrollBy(0, -80));
    await shot(page, info, "42-admin-photos");
    await page.getByRole("button", { name: "Delete photo" }).first().click();
    await expect(page.getByRole("region", { name: "Photos" }).getByRole("button", { name: "Delete", exact: true })).toBeVisible();
    await shot(page, info, "43-admin-photo-delete-confirm");
  } finally {
    // Leave the seeded hotel as it was: remove the uploads and restore the order.
    const after: { id: number; uploaded: boolean }[] = await (await request.get(`${API}/api/admin/hotels/${hotelId}/photos`, { headers: adminHeaders })).json();
    for (const p of after.filter((p) => p.uploaded)) {
      await request.delete(`${API}/api/admin/hotels/${hotelId}/photos/${p.id}`, { headers: adminHeaders });
    }
    if (before.length) {
      await request.put(`${API}/api/admin/hotels/${hotelId}/photos/order`, { headers: adminHeaders, data: { ids: before.map((p) => p.id) } });
    }
  }
});

/*
 * Staging bar, /admin/funnel and /admin/today. Run against a web build with NEXT_PUBLIC_APP_ENV=staging and a
 * backend whose clock (and SHOT_CLOCK) is an evening, so "last night" on /admin/today has bookings.
 */
test("@v8 staging bar", async ({ page, request }, info) => {
  await atShotClock(page);
  await page.goto("/");
  await expect(page.getByRole("note").filter({ hasText: "Staging · test payments" })).toBeVisible();
  await expect(page.locator("article").first()).toBeVisible();
  await shot(page, info, "44-staging-home");
  await confirmed(page, request);
  await shot(page, info, "45-staging-pass");
});

test("@v8 funnel", async ({ page, request, browser }, info) => {
  // A visitor from a reel books and checks in; another only looks at a hotel.
  await atShotClock(page);
  await page.goto("/?utm_source=instagram&utm_medium=reel&utm_campaign=manali-night-01");
  await expect(page.locator("article").first()).toBeVisible();
  await checkedIn(page, request);
  const other = await browser.newPage({ viewport: page.viewportSize()! });
  await atShotClock(other);
  await other.goto("/?utm_source=youtube&utm_medium=short");
  await other.locator("article a").first().click();
  await expect(other.getByRole("heading", { level: 1 })).toBeVisible();
  await other.close();

  await signIn(page);
  await page.goto("/admin/funnel");
  await expect(page.getByRole("heading", { name: "Funnel" })).toBeVisible();
  await expect(page.getByText("Searched")).toBeVisible();
  await shot(page, info, "46-admin-funnel", true);
  await page.getByLabel("UTM source").selectOption("instagram");
  await expect(page.getByLabel("UTM source")).toHaveValue("instagram");
  await quiet(page);
  await shot(page, info, "46b-admin-funnel-instagram", true);
});

test("@v8 today", async ({ page }, info) => {
  await signIn(page);
  await page.goto("/admin/today");
  await expect(page.getByRole("heading", { name: /Night of/ })).toBeVisible();
  await expect(page.getByText("Your commission")).toBeVisible();
  await shot(page, info, "47-admin-today", true);
});

/*
 * Reviews, groups, shared stays and town pages. Two passes against a local backend:
 *   @v9        the real clock: tonight's stay is booked, confirmed, shared and checked in; the cards ("New"),
 *              a group's two-room result and a town page.
 *   @v9-after  the backend restarted with latestay.dev.clock-at the next morning after 09:00, and SHOT_CLOCK the
 *              same: the stay from @v9 is over, so its pass offers the review form.
 */
// Kept outside test-results/, which Playwright empties at the start of every run.
const v9Booking = (info: TestInfo) => `screenshots/.v9-booking-${info.project.name}.txt`;

test("@v9 hotel cards show New until three reviews", async ({ page }, info) => {
  await page.goto("/");
  await expect(page.locator("article").first()).toBeVisible();
  await expect(page.locator("article").first().getByText("New", { exact: true })).toBeVisible();
  await shot(page, info, "48-home-new");
});

test("@v9 a group gets two rooms", async ({ page }, info) => {
  await page.goto("/?place=manali&arrive=23:30&leave=09:00&guests=6");
  await expect(page.locator("article").first()).toBeVisible();
  await expect(page.getByText("for 2 rooms").first()).toBeVisible();
  // Past the search pill, so the first card's price line clears the floating map button.
  await page.evaluate(() => window.scrollTo(0, 260));
  await shot(page, info, "49-group-results");
  await page.locator("article a").first().click();
  await expect(page.getByText(/^2 rooms: /).filter({ visible: true }).first()).toBeVisible();
  await shot(page, info, "49b-group-hotel", true);
});

test("@v9 share with family", async ({ page, request, browser }, info) => {
  // The share sheet can't be driven here: the pass falls back to copying the link, then shows it.
  await page.addInitScript(() => Object.defineProperty(navigator, "share", { value: undefined }));
  const id = await confirmed(page, request);
  await page.getByRole("button", { name: "Share with family" }).click();
  await expect(page.getByRole("button", { name: "Turn off link" })).toBeVisible();
  await shot(page, info, "50-pass-share", true);
  const url = (await page.locator("p.break-all").textContent())!.trim();
  const path = new URL(url).pathname;

  const family = await browser.newPage({ viewport: page.viewportSize()! });
  await family.goto(path);
  await expect(family.getByText("Confirmed by the hotel")).toBeVisible();
  const booking = await (await request.get(`${API}/api/bookings/${id}`)).json();
  const text = await family.locator("main").innerText();
  expect(text).not.toContain(booking.code);
  expect(text).not.toContain("₹");
  expect(text).not.toContain(booking.guestName);
  await shot(family, info, "51-shared-stay", true);

  await request.post(`${API}/api/dev/whatsapp/inbound`, { data: { from: booking.hotel.phone, text: booking.code } });
  await family.reload();
  await expect(family.getByText("Checked in", { exact: true })).toBeVisible();
  await shot(family, info, "51b-shared-checked-in");
  await family.close();
  writeFileSync(v9Booking(info), id);
});

test("@v9 town page", async ({ page, request }, info) => {
  await page.goto("/towns/manali");
  await expect(page.getByRole("heading", { level: 1, name: "Late-night rooms in Manali" })).toBeVisible();
  await expect(page.getByRole("button", { name: "I need a room now" })).toBeVisible();
  await shot(page, info, "52-town", true);
  const og = page.locator('meta[property="og:image"]');
  const image = await request.get((await og.getAttribute("content"))!.replace(/^https?:\/\/[^/]+/, ""));
  expect(image.headers()["content-type"]).toBe("image/png");
  writeFileSync(`screenshots/${info.project.name}-52b-town-og.png`, await image.body());
});

test("@v9-after rate your stay", async ({ page }, info) => {
  await atShotClock(page);
  const id = readFileSync(v9Booking(info), "utf8").trim();
  await page.goto(`/pass/${id}`);
  await expect(page.getByRole("link", { name: /Rate your stay/ })).toBeVisible();
  await shot(page, info, "53-pass-rate", true);
  await page.getByRole("link", { name: /Rate your stay/ }).click();
  await expect(page.getByRole("heading", { name: /^How was / })).toBeVisible();
  await shot(page, info, "54-review-form", true);

  await page.getByLabel(/^4 stars/).check({ force: true });
  await page.getByRole("button", { name: "Warm" }).click();
  await page.getByRole("button", { name: "Friendly desk" }).click();
  await page.getByLabel("Anything else?").fill("Desk answered at 1 am, call 98765 43210 if stuck");
  await page.getByRole("button", { name: "Send rating" }).click();
  await expect(page.getByText("Please leave out phone numbers.")).toBeVisible();
  await shot(page, info, "54b-review-error", true);
  await page.getByLabel("Anything else?").fill("Desk answered at 1 am and the room was warm. Hot water took a while.");
  await shot(page, info, "54c-review-filled", true);
  await page.getByRole("button", { name: "Send rating" }).click();
  await expect(page.getByRole("heading", { name: /^You rated / })).toBeVisible();
  await shot(page, info, "55-review-done");
});
