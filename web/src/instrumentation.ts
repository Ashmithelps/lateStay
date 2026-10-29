import type { Instrumentation } from "next";

// Server-side errors to Sentry when SENTRY_DSN is set (errors only, no personal data).
const dsn = process.env.SENTRY_DSN;

export async function register() {
  if (!dsn || process.env.NEXT_RUNTIME !== "nodejs") return;
  const [Sentry, { scrubEvent }] = await Promise.all([import("@sentry/nextjs"), import("@/lib/sentry-scrub")]);
  Sentry.init({
    dsn,
    environment: process.env.SENTRY_ENVIRONMENT ?? "production",
    // No personal data: no user, cookies, headers, bodies or query strings.
    dataCollection: { userInfo: false, cookies: false, httpHeaders: false, httpBodies: [], urlQueryParams: false },
    tracesSampleRate: 0,
    beforeSend: scrubEvent,
  });
}

export const onRequestError: Instrumentation.onRequestError = async (...args) => {
  if (!dsn) return;
  const Sentry = await import("@sentry/nextjs");
  Sentry.captureRequestError(...args);
};
