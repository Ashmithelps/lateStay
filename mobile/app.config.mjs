/**
 * The app's build settings, from env (docs/MOBILE.md). One place, shared by capacitor.config.ts and
 * scripts/configure.mjs:
 *   APP_ENV   production (default) or staging
 *   APP_ID    bundle / application ID, default app.latestay
 *   SERVER_URL the web app the app loads; default https://latestay.in, or https://staging.latestay.in on staging
 * The version comes only from mobile/package.json.
 */
import { readFileSync } from "node:fs";

const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8"));

export const appEnv = process.env.APP_ENV === "staging" ? "staging" : "production";
export const appId = process.env.APP_ID?.trim() || "app.latestay";
export const serverUrl = (
  process.env.SERVER_URL?.trim() || (appEnv === "staging" ? "https://staging.latestay.in" : "https://latestay.in")
).replace(/\/+$/, "");
export const version = pkg.version;

/** 1.2.3 -> 10203: always increasing with the version, as both stores require. */
export function buildNumber(v = version) {
  const [major, minor, patch] = v.split(".").map((n) => Number.parseInt(n, 10));
  if (![major, minor, patch].every((n) => Number.isInteger(n) && n >= 0 && n < 100)) {
    throw new Error(`version must be x.y.z with each part 0-99, got ${v}`);
  }
  return major * 10000 + minor * 100 + patch;
}

/** Hosts the app's main view may show: our web apps, and Razorpay's checkout pages. Everything else opens outside. */
export const allowNavigation = [
  "latestay.in",
  "www.latestay.in",
  "staging.latestay.in",
  "checkout.razorpay.com",
  "api.razorpay.com",
];
