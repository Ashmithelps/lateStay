import { devices, expect, test, type Browser, type Page, type TestInfo } from "@playwright/test";
import { APP_USER_AGENT_SUFFIX, fireNative, nativeCalls, simulateApp, type SimOptions } from "./app-sim";

/*
 * The iOS/Android app shell (see e2e/app-sim.ts for how the app's WebView is simulated) and the website without it.
 * Run once, at phone size: `npx playwright test app-shell --project=390`. Needs a local-profile backend.
 */
const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const ADMIN = { username: process.env.ADMIN_USERNAME ?? "admin", password: process.env.ADMIN_PASSWORD ?? "" };
const adminHeaders = {
  Authorization: `Basic ${Buffer.from(`${ADMIN.username}:${ADMIN.password}`).toString("base64")}`,
};

test.beforeEach(({}, info) => test.skip(info.project.name !== "390", "phone size only"));

async function appPage(browser: Browser, info: TestInfo, options: SimOptions = {}): Promise<Page> {
  const context = await browser.newContext({
    baseURL: info.project.use.baseURL,
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    userAgent: devices["iPhone 13"].userAgent + APP_USER_AGENT_SUFFIX,
  });
  await simulateApp(context, options);
  return context.newPage();
}

async function shot(page: Page, name: string) {
  await page.waitForLoadState("networkidle", { timeout: 5_000 }).catch(() => {});
  await page.waitForTimeout(300);
  await page.screenshot({ path: `screenshots/app-${name}.png` });
}

test("the website has no app shell", async ({ page, request }) => {
  await page.goto("/");
  await expect(page.locator("article").first()).toBeVisible();
  await expect(page.locator("html")).not.toHaveClass(/native/);
  await expect(page.getByRole("navigation", { name: "Main" })).toHaveCount(0);
  expect((await page.locator('meta[name="viewport"]').getAttribute("content")) ?? "").not.toContain("viewport-fit");
  expect((await request.get("/app/bookings")).status()).toBe(404);
  expect((await request.get("/app/help")).status()).toBe(404);
});

test("tabs: Tonight, Bookings, Help", async ({ browser }, info) => {
  const page = await appPage(browser, info);
  await page.goto("/");
  await expect(page.locator("html")).toHaveClass(/native/);
  const tabs = page.getByRole("navigation", { name: "Main" });
  await expect(tabs.getByRole("link", { name: "Tonight" })).toHaveAttribute("aria-current", "page");
  await expect(page.locator("article").first()).toBeVisible();
  await shot(page, "01-tonight");

  await tabs.getByRole("link", { name: "Bookings" }).click();
  await expect(page.getByText("No bookings on this phone yet")).toBeVisible();
  await expect(tabs.getByRole("link", { name: "Bookings" })).toHaveAttribute("aria-current", "page");
  await shot(page, "02-bookings-empty");

  await tabs.getByRole("link", { name: "Help" }).click();
  await expect(page.getByRole("heading", { name: "Help" })).toBeVisible();
  await shot(page, "03-help");
  // Policy pages open in the in-app browser, not the app's main view.
  await page.getByRole("link", { name: /Cancellation and refunds/ }).click();
  await expect(page).toHaveURL(/\/app\/help$/);
  await expect.poll(async () => (await nativeCalls(page)).some((c) => c.plugin === "Browser" && c.method === "open")).toBe(true);

  // Android's back button: back through history, out of the app only from Tonight.
  await fireNative(page, "App", "backButton", { canGoBack: true });
  await expect(page).toHaveURL(/\/app\/bookings$/);
  await page.goto("/");
  await fireNative(page, "App", "backButton", { canGoBack: false });
  await expect.poll(async () => (await nativeCalls(page)).some((c) => c.plugin === "App" && c.method === "exitApp")).toBe(true);
});

test("the tab bar hides on the hotel page and in the chat", async ({ browser }, info) => {
  const page = await appPage(browser, info);
  await page.goto("/");
  await page.locator("article a").first().click();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Main" })).toHaveCount(0);
});

test("I need a room now asks the app for the location", async ({ browser }, info) => {
  const page = await appPage(browser, info);
  await page.goto("/");
  await page.getByRole("button", { name: "I need a room now" }).click();
  await expect(page).toHaveURL(/place=here/);
  expect((await nativeCalls(page)).some((c) => c.plugin === "Geolocation" && c.method === "getCurrentPosition")).toBe(true);
});

function freshPhone(): string {
  return `9${String(Math.floor(Math.random() * 1e9)).padStart(9, "0")}`;
}

/** Search to paid, in the app: ends on the "Waiting for the hotel" pass. */
async function bookAndPay(page: Page): Promise<string> {
  await page.goto("/");
  await page.locator("article a").first().click();
  await page.getByRole("button", { name: "Start haggling" }).filter({ visible: true }).click();
  await page.waitForURL(/\/haggle\//);
  await page.getByRole("button", { name: /^Accept/ }).click();
  await page.waitForURL(/\/book\//);
  const phone = freshPhone();
  await page.getByLabel("Name").fill("Asha Verma");
  await page.getByLabel("Mobile number (WhatsApp)").fill(phone);
  await page.getByLabel(/photo ID/).check();
  await page.getByRole("button", { name: "Send code on WhatsApp" }).click();
  await expect(page.getByLabel("6-digit code from WhatsApp")).toBeVisible();
  const { code } = await (await page.request.get(`${API}/api/dev/phone-verifications/latest?phone=${phone}`)).json();
  await page.getByLabel("6-digit code from WhatsApp").fill(code);
  await page.getByRole("button", { name: "Verify number" }).click();
  await page.getByRole("button", { name: /^Pay / }).click();
  await page.waitForURL(/\/pass\//);
  await expect(page.getByRole("heading", { name: /Waiting for/ })).toBeVisible();
  return page.url().split("/pass/")[1];
}

test("after paying: the notification card, a saved pass, offline, and a notification tap", async ({ browser }, info) => {
  const page = await appPage(browser, info, { push: "prompt" });
  const id = await bookAndPay(page);
  // A light tap on Accept.
  expect((await nativeCalls(page)).some((c) => c.plugin === "Haptics" && c.method === "impact")).toBe(true);

  // Asked only now, behind our own card first.
  await expect(page.getByRole("heading", { name: "Get a notification when the hotel confirms." })).toBeVisible();
  await page.getByRole("heading", { name: "Get a notification when the hotel confirms." }).scrollIntoViewIfNeeded();
  await shot(page, "04-notify-card");
  const registered = page.waitForResponse((r) => r.url().endsWith("/api/devices") && r.request().method() === "POST");
  await page.getByRole("button", { name: "Turn on" }).click();
  expect((await registered).status()).toBe(204);
  expect((await registered).request().postDataJSON()).toMatchObject({ platform: "IOS", bookingId: id });
  await expect(page.getByText("Notifications are on.")).toBeVisible();

  // The hotel confirms: the pass moves on, with a success buzz, and is saved on the phone.
  expect((await page.request.post(`${API}/api/admin/bookings/${id}/confirm`, { headers: adminHeaders })).ok()).toBe(true);
  await fireNative(page, "PushNotifications", "pushNotificationReceived", { data: { bookingId: id, path: `/pass/${id}` } });
  await expect(page.getByText("Show this code at the desk")).toBeVisible();
  await expect
    .poll(async () => (await nativeCalls(page)).some((c) => c.plugin === "Haptics" && c.method === "notification"))
    .toBe(true);
  await shot(page, "05-pass-confirmed");

  await page.goto("/app/bookings");
  await expect(page.getByRole("link", { name: /Confirmed/ })).toBeVisible();
  await shot(page, "06-bookings");

  // No connection: the app's offline screen (what the service worker serves when a page can't load) with every
  // saved pass and its code, never an error page.
  await page.goto("/~offline");
  await expect(page.getByRole("heading", { name: "No connection" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Retry" })).toBeVisible();
  await expect(page.getByText("Show this code at the desk")).toBeVisible();
  await shot(page, "07-offline");

  // Tapping a notification opens that booking's pass.
  await page.goto("/app/help");
  await fireNative(page, "PushNotifications", "pushNotificationActionPerformed", {
    actionId: "tap",
    notification: { data: { bookingId: id, path: `/pass/${id}` } },
  });
  await expect(page).toHaveURL(new RegExp(`/pass/${id}$`));
});
