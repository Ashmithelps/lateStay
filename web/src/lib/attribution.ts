/*
 * First-touch attribution and the anonymous analytics id, kept in this browser only.
 *
 * - UTM tags (utm_source, utm_medium, utm_campaign) from the first visit that had any, never overwritten, so each
 *   reel link can be measured. Same rules as the backend: short lower-case tags, nothing with 4+ digits in a row.
 * - A random analytics id (no phone number, name or anything personal) that ties this browser's funnel steps
 *   together.
 */

const UTM_KEY = "latestay:utm";
const AID_KEY = "latestay:aid";
const TAG = /^[a-z0-9][a-z0-9._+-]{0,99}$/;

export type FirstTouch = { utmSource: string | null; utmMedium: string | null; utmCampaign: string | null };

export function cleanTag(value: string | null | undefined): string | null {
  if (!value) return null;
  const v = value.trim().toLowerCase();
  return TAG.test(v) && !/\d{4,}/.test(v) ? v : null;
}

/** Reads UTM tags from a URL's query, if it has any usable ones. */
export function utmFrom(search: string): FirstTouch | null {
  const params = new URLSearchParams(search);
  const touch = {
    utmSource: cleanTag(params.get("utm_source")),
    utmMedium: cleanTag(params.get("utm_medium")),
    utmCampaign: cleanTag(params.get("utm_campaign")),
  };
  return touch.utmSource || touch.utmMedium || touch.utmCampaign ? touch : null;
}

/** Keeps the first UTM tags this browser ever arrived with. */
export function rememberFirstTouch(search: string, storage: Storage | null = safeStorage()) {
  const touch = utmFrom(search);
  if (!touch || !storage) return;
  try {
    if (!storage.getItem(UTM_KEY)) storage.setItem(UTM_KEY, JSON.stringify(touch));
  } catch {
    // storage full or blocked
  }
}

export function firstTouch(storage: Storage | null = safeStorage()): FirstTouch {
  try {
    const raw = storage?.getItem(UTM_KEY);
    if (raw) {
      const t = JSON.parse(raw) as FirstTouch;
      return { utmSource: cleanTag(t.utmSource), utmMedium: cleanTag(t.utmMedium), utmCampaign: cleanTag(t.utmCampaign) };
    }
  } catch {
    // unreadable
  }
  return { utmSource: null, utmMedium: null, utmCampaign: null };
}

/** This browser's random analytics id (16 bytes, base64url), made on first use. */
export function analyticsId(storage: Storage | null = safeStorage()): string {
  try {
    const existing = storage?.getItem(AID_KEY);
    if (existing && /^[A-Za-z0-9_-]{8,64}$/.test(existing)) return existing;
  } catch {
    // fall through to a new id
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const id = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  try {
    storage?.setItem(AID_KEY, id);
  } catch {
    // an id for this page only
  }
  return id;
}

function safeStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}
