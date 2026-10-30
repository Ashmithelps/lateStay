import { defineConfig, devices } from "@playwright/test";

/**
 * Screenshot review: `npm run screenshots` captures every page at 390 px and 1280 px into screenshots/.
 * Needs the backend running with the local profile (seeded hotels) on :8080.
 */
/** WEB_PORT lets the run use its own `next start` next to a `next dev` already on 3000. */
const port = Number(process.env.WEB_PORT ?? 3000);

export default defineConfig({
  testDir: "./e2e",
  outputDir: "test-results",
  timeout: 120_000,
  workers: 1,
  fullyParallel: false,
  reporter: "list",
  use: { baseURL: `http://localhost:${port}`, trace: "off" },
  projects: [
    {
      name: "390",
      use: {
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 2,
        isMobile: true,
        hasTouch: true,
        userAgent: devices["iPhone 13"].userAgent,
      },
    },
    { name: "1280", use: { viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 } },
  ],
  webServer: { command: `npx next start -p ${port}`, url: `http://localhost:${port}`, reuseExistingServer: true, timeout: 120_000 },
});
