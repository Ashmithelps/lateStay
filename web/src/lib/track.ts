import { API_URL } from "./api";
import { analyticsId, cleanTag, firstTouch } from "./attribution";

/*
 * Funnel events from the browser. They go to our own API (POST /api/events), which keeps them and forwards them to
 * PostHog from the server, so no analytics script runs here and PostHog never sees this browser or its IP.
 * Each event takes only the properties typed below; the backend drops anything else, and anything that could be a
 * phone number, a name or a code.
 */

type Props = {
  room_now_tapped: { located: boolean };
  search_started: { place: string; guests: number; room_now: boolean };
  results_viewed: { results: number; room_now: boolean };
  hotel_opened: { hotel: string };
  haggle_started: { hotel: string };
  counter_sent: { round: number };
  offer_accepted: { price_band: string };
  code_sent: Record<string, never>;
  code_verified: Record<string, never>;
  payment_started: { promo: boolean };
};

export type TrackedEvent = keyof Props;

type Context = { stayRequestId?: string | null; negotiationId?: string | null };

export function track<E extends TrackedEvent>(name: E, properties: Props[E], context: Context = {}): void {
  if (typeof window === "undefined") return;
  try {
    const body = JSON.stringify({
      name,
      distinctId: analyticsId(),
      stayRequestId: context.stayRequestId ?? null,
      negotiationId: context.negotiationId ?? null,
      utmSource: firstTouch().utmSource,
      properties,
    });
    // keepalive: the event still goes out when the page navigates away (e.g. to Razorpay).
    void fetch(`${API_URL}/api/events`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      keepalive: true,
      credentials: "omit",
    }).catch(() => {});
  } catch {
    // analytics must never break the page
  }
}

/** ₹1,300 -> "1k-1.5k", as the backend bands prices: the exact amount is never sent. */
export function priceBand(rupees: number): string {
  const low = Math.floor(Math.max(0, rupees) / 500) * 500;
  const k = (n: number) => `${Number((n / 1000).toFixed(3))}k`;
  return `${k(low)}-${k(low + 500)}`;
}

/** A place for the search_started event: a town id, or "here". Never coordinates. */
export function placeTag(place: string): string {
  return cleanTag(place) ?? "other";
}
