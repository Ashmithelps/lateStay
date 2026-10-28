import { expect, test, type Page, type TestInfo } from "@playwright/test";

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
  await expect(page.getByRole("button", { name: /^Pay / })).toBeVisible();
}

/** Books and pays (the local backend's fake gateway stands in for Razorpay Checkout); ends on the pass. */
async function bookAndPay(page: Page): Promise<string> {
  await agree(page);
  await page.getByLabel("Name").fill("Asha Verma");
  await page.getByLabel("Mobile number").fill("98765 43210");
  await page.getByLabel(/photo ID/).check();
  await page.getByRole("button", { name: /^Pay / }).click();
  await page.waitForURL(/\/pass\//);
  await expect(page.getByRole("heading", { name: /Waiting for/ })).toBeVisible();
  return page.url().split("/pass/")[1];
}

test("book", async ({ page }, info) => {
  await agree(page);
  await shot(page, info, "10-book");
  await shot(page, info, "10-book-full", true);
  await page.getByLabel("Mobile number").fill("12345");
  await page.getByRole("button", { name: /^Pay / }).click();
  await expect(page.getByText("Enter a 10-digit Indian mobile number.")).toBeVisible();
  await shot(page, info, "11-book-errors", true);
});

test("pass: waiting", async ({ page }, info) => {
  await bookAndPay(page);
  await shot(page, info, "12-pass-waiting", true);
});

test("pass: confirmed", async ({ page, request }, info) => {
  const id = await bookAndPay(page);
  expect((await request.post(`${API}/api/admin/bookings/${id}/confirm`, { headers: adminHeaders })).ok()).toBe(true);
  await expect(page.getByText("Show this code at the desk")).toBeVisible({ timeout: 15_000 });
  await shot(page, info, "13-pass-confirmed", true);
  await page.goto("/");
  await expect(page.getByRole("link", { name: "Your booking" }).filter({ visible: true })).toBeVisible();
  await shot(page, info, "14-home-your-booking");
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

  // Something to confirm on the board, and a live negotiation to read.
  await bookAndPay(page);
  await startHaggling(page);
  await send(page, "700 mein de do");

  await signIn(page);
  await expect(page.getByRole("button", { name: "Confirm" }).first()).toBeVisible();
  await shot(page, info, "17-admin-bookings", true);

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
  await expect(page.getByText("2 room khali hai")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/Floor rules/).first()).toBeVisible();
  await shot(page, info, "19-admin-hotels");
  await shot(page, info, "19-admin-hotels-full", true);
});
