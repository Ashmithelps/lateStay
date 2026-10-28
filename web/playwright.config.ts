import { defineConfig, devices } from "@playwright/test";

/**
 * Screenshot review: `npm run screenshots` captures every page at 390 px and 1280 px into screenshots/.
 * Needs the backend running with the local profile (seeded hotels) on :8080.
 */
export default defineConfig({
  testDir: "./e2e",
  outputDir: "test-results",
  timeout: 120_000,
  workers: 1,
  fullyParallel: false,
  reporter: "list",
  use: { baseURL: "http://localhost:3000", trace: "off" },
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
  webServer: { command: "npx next start -p 3000", url: "http://localhost:3000", reuseExistingServer: true, timeout: 120_000 },
});
