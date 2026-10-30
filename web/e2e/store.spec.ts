import { devices, expect, test, type Page } from "@playwright/test";
import { APP_USER_AGENT_SUFFIX, simulateApp } from "./app-sim";

/*
 * Store screenshots of the app (simulated WebView, e2e/app-sim.ts) at the stores' required sizes, into
 * mobile/store/screenshots/<device>/. For the listing: build the web app against staging's API and set
 * STORE_PASS_ID to a real confirmed booking there (staging has no dev endpoints to book with). Locally it books
 * through the local backend's dev endpoints.
 *   npx playwright test store --project=390
 */
const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const ADMIN = { username: process.env.ADMIN_USERNAME ?? "admin", password: process.env.ADMIN_PASSWORD ?? "" };
const adminHeaders = { Authorization: `Basic ${Buffer.from(`${ADMIN.username}:${ADMIN.password}`).toString("base64")}` };

const DEVICES = [
  { name: "iphone-6.9", width: 440, height: 956, scale: 3, ua: devices["iPhone 15 Pro Max"].userAgent, platform: "ios" },
  { name: "iphone-6.5", width: 428, height: 926, scale: 3, ua: devices["iPhone 13 Pro Max"].userAgent, platform: "ios" },
  { name: "android-phone", width: 360, height: 640, scale: 3, ua: devices["Pixel 7"].userAgent, platform: "android" },
] as const;

async function shot(page: Page, device: string, name: string) {
  await page.waitForLoadState("networkidle", { timeout: 5_000 }).catch(() => {});
  await page.waitForTimeout(400);
  await page.screenshot({ path: `../mobile/store/screenshots/${device}/${name}.png` });
}

async function confirmedBooking(page: Page): Promise<string> {
  if (process.env.STORE_PASS_ID) return process.env.STORE_PASS_ID;
  await page.goto("/");
  await page.locator("article a").first().click();
  await page.getByRole("button", { name: "Start haggling" }).filter({ visible: true }).click();
  await page.waitForURL(/\/haggle\//);
  await page.getByRole("button", { name: /^Accept/ }).click();
  await page.waitForURL(/\/book\//);
  const phone = `9${String(Math.floor(Math.random() * 1e9)).padStart(9, "0")}`;
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
  const id = page.url().split("/pass/")[1];
  await page.request.post(`${API}/api/admin/bookings/${id}/confirm`, { headers: adminHeaders });
  return id;
}

for (const d of DEVICES) {
  test(`store screenshots: ${d.name}`, async ({ browser }, info) => {
    test.skip(info.project.name !== "390", "once");
    const context = await browser.newContext({
      baseURL: info.project.use.baseURL,
      viewport: { width: d.width, height: d.height },
      deviceScaleFactor: d.scale,
      isMobile: true,
      hasTouch: true,
      userAgent: d.ua + APP_USER_AGENT_SUFFIX,
    });
    await simulateApp(context, { platform: d.platform, push: "granted" });
    const page = await context.newPage();

    await page.goto("/");
    await expect(page.locator("article").first()).toBeVisible();
    await shot(page, d.name, "1-tonight");

    await page.locator("article a").first().click();
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await shot(page, d.name, "2-hotel");

    await page.getByRole("button", { name: "Start haggling" }).filter({ visible: true }).click();
    await page.waitForURL(/\/haggle\//);
    await page.getByLabel("Message the front desk").fill("Can you do 700?");
    await page.getByRole("button", { name: "Send" }).click();
    await expect(page.locator("ol > li").nth(2)).toBeVisible({ timeout: 30_000 });
    await shot(page, d.name, "3-haggle");

    const id = await confirmedBooking(page);
    await page.goto(`/pass/${id}`);
    await expect(page.getByText("Show this code at the desk")).toBeVisible();
    await shot(page, d.name, "4-pass");

    await page.goto("/app/bookings");
    await expect(page.getByRole("heading", { name: "Bookings" })).toBeVisible();
    await shot(page, d.name, "5-bookings");
    await context.close();
  });
}
