import base from "./playwright.config";
import { defineConfig } from "@playwright/test";

/**
 * "The website is unchanged": pixel comparison of public pages against baselines taken before a change.
 * UNCHANGED_DIR (default e2e/.unchanged, git-ignored) holds the baselines; take them with --update-snapshots on the
 * old code, then run again on the new code. Needs a local-profile backend, like the screenshot run.
 */
export default defineConfig({
  ...base,
  testMatch: "unchanged.spec.ts",
  snapshotPathTemplate: `${process.env.UNCHANGED_DIR ?? "e2e/.unchanged"}/{projectName}/{arg}{ext}`,
  expect: { toHaveScreenshot: { maxDiffPixels: 0, animations: "disabled", caret: "hide" } },
});
