import { scrubEvent } from "@/lib/sentry-scrub";

// Errors only, and only when a DSN is set. Sentry's code loads once the browser is idle, so it never competes
// with the first hotel photo on a slow connection (an error in the first seconds is the trade-off).
const dsn = process.env.NEXT_PUBLIC_SENTRY_DSN;

if (dsn && typeof window !== "undefined") {
  const start = () =>
    import("@sentry/nextjs").then((Sentry) =>
      Sentry.init({
        dsn,
        environment: process.env.NEXT_PUBLIC_SENTRY_ENVIRONMENT ?? "production",
        // No personal data: no user, cookies, headers, bodies or query strings.
        dataCollection: { userInfo: false, cookies: false, httpHeaders: false, httpBodies: [], urlQueryParams: false },
        tracesSampleRate: 0,
        beforeSend: scrubEvent,
      }),
    );
  if ("requestIdleCallback" in window) window.requestIdleCallback(() => void start(), { timeout: 8000 });
  else setTimeout(() => void start(), 4000);
}
