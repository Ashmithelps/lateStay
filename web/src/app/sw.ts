/// <reference lib="esnext" />
/// <reference lib="webworker" />
import { defaultCache } from "@serwist/turbopack/worker";
import type { PrecacheEntry, RuntimeCaching, SerwistGlobalConfig } from "serwist";
import { ExpirationPlugin, NetworkFirst, NetworkOnly, Serwist } from "serwist";

declare global {
  interface WorkerGlobalScope extends SerwistGlobalConfig {
    __SW_MANIFEST: (PrecacheEntry | string)[] | undefined;
  }
}

declare const self: ServiceWorkerGlobalScope;

// Replaced at build time by the route handler (esbuild define).
declare const __API_ORIGIN__: string;
const apiOrigin = new URL(__API_ORIGIN__).origin;

/**
 * What works offline: the app shell (precached), pages already visited (Serwist's defaults), the last search
 * results and recent booking passes. Admin, dev and webhook traffic is never cached, and nothing else from the
 * API is either (chat and prices must be live).
 */
const latestay: RuntimeCaching[] = [
  {
    matcher: ({ url, sameOrigin }) => sameOrigin && url.pathname.startsWith("/admin"),
    handler: new NetworkOnly(),
  },
  {
    matcher: ({ url }) =>
      url.origin === apiOrigin && /^\/api\/(admin|dev|webhooks)\//.test(url.pathname),
    handler: new NetworkOnly(),
  },
  {
    matcher: ({ url }) => url.origin === apiOrigin && url.pathname === "/api/search",
    method: "GET",
    handler: new NetworkFirst({
      cacheName: "search-results",
      networkTimeoutSeconds: 6,
      plugins: [new ExpirationPlugin({ maxEntries: 3, maxAgeSeconds: 24 * 60 * 60 })],
    }),
  },
  {
    matcher: ({ url }) => url.origin === apiOrigin && /^\/api\/bookings\/[0-9a-f-]{36}$/.test(url.pathname),
    method: "GET",
    handler: new NetworkFirst({
      cacheName: "booking-passes",
      networkTimeoutSeconds: 6,
      plugins: [new ExpirationPlugin({ maxEntries: 5, maxAgeSeconds: 7 * 24 * 60 * 60 })],
    }),
  },
  { matcher: ({ url }) => url.origin === apiOrigin, handler: new NetworkOnly() },
];

const serwist = new Serwist({
  precacheEntries: self.__SW_MANIFEST,
  skipWaiting: true,
  clientsClaim: true,
  navigationPreload: true,
  runtimeCaching: [...latestay, ...defaultCache],
  fallbacks: {
    entries: [
      {
        url: "/~offline",
        matcher({ request }) {
          return request.destination === "document";
        },
      },
    ],
  },
});

serwist.addEventListeners();
