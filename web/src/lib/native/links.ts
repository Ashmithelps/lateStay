/**
 * Where a link goes in the app. Nothing external opens inside the app's main view:
 * - our own pages stay in the app, except the policy pages, which open in the in-app browser;
 * - phone numbers open the dialler, maps the system maps app, WhatsApp the WhatsApp app;
 * - anything else opens in the system browser.
 */
export type LinkTarget =
  | { kind: "in-app" }
  | { kind: "in-app-browser"; url: string }
  | { kind: "system"; url: string };

export const POLICY_PATHS = ["/privacy", "/terms", "/refund-policy", "/contact"];

export function linkTarget(href: string, origin: string): LinkTarget {
  let url: URL;
  try {
    url = new URL(href, origin);
  } catch {
    return { kind: "in-app" };
  }
  if (url.protocol === "tel:" || url.protocol === "mailto:" || url.protocol === "geo:" || url.protocol === "maps:") {
    return { kind: "system", url: url.toString() };
  }
  if (url.origin === origin) {
    return POLICY_PATHS.includes(url.pathname) ? { kind: "in-app-browser", url: url.toString() } : { kind: "in-app" };
  }
  return { kind: "system", url: url.toString() };
}

/** Directions in the system maps app: Apple Maps on iOS, the default maps app on Android. */
export function mapsUrl(platform: "ios" | "android", lat: number, lng: number, name: string): string {
  return platform === "ios"
    ? `maps://?daddr=${lat},${lng}&q=${encodeURIComponent(name)}`
    : `geo:${lat},${lng}?q=${lat},${lng}(${encodeURIComponent(name)})`;
}
