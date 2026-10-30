import { expect, test } from "@playwright/test";

/** Public pages, with the browser clock fixed so "tonight" and times render the same on every run. */
const PAGES: [string, string][] = [
  ["home", "/?place=manali&arrive=23:30&leave=09:00&guests=2"],
  ["home-group", "/?place=manali&arrive=23:30&leave=09:00&guests=6"],
  ["hotel", "/hotels/deodar-nest"],
  ["towns", "/towns"],
  ["town", "/towns/manali"],
  ["privacy", "/privacy"],
  ["terms", "/terms"],
  ["refund", "/refund-policy"],
  ["contact", "/contact"],
  ["admin-sign-in", "/admin"],
  ["owner-sign-in", "/owner"],
];

for (const [name, path] of PAGES) {
  test(`unchanged: ${name}`, async ({ page }) => {
    await page.clock.install({ time: new Date("2026-10-30T20:00:00+05:30") });
    await page.goto(path);
    await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});
    await page.waitForTimeout(500);
    // Map tiles come from a third-party server and can arrive in any order: masked.
    await expect(page).toHaveScreenshot(`${name}.png`, {
      fullPage: true,
      timeout: 15_000,
      mask: [page.locator(".leaflet-container")],
    });
  });
}
