import type { BookingView } from "@/lib/api";

/**
 * Passes saved on this device (Preferences in the app), so Bookings and a pass open with no network. Only what the
 * pass shows: hotel, address, times, room, guests, code and amounts. Kept until 30 days after the stay.
 */
export type SavedPass = {
  id: string;
  status: BookingView["status"];
  hotel: { name: string; address: string; town: string; lat: number; lng: number; phone: string };
  sessionId: string;
  roomType: string;
  guestName: string;
  guests: number;
  arriveAt: string;
  arriveBy: string;
  leaveAt: string;
  code: string | null;
  agreedPrice: number;
  depositAmount: number;
  payAtDesk: number;
  savedAt: number;
};

export const STORAGE_KEY = "latestay.passes";
export const KEEP_AFTER_STAY_MS = 30 * 24 * 60 * 60 * 1000;
export const MAX_PASSES = 20;

export function toSavedPass(b: BookingView, now: number): SavedPass {
  return {
    id: b.id,
    status: b.status,
    hotel: { name: b.hotel.name, address: b.hotel.address, town: b.hotel.town, lat: b.hotel.lat, lng: b.hotel.lng, phone: b.hotel.phone },
    sessionId: b.sessionId,
    roomType: b.roomType,
    guestName: b.guestName,
    guests: b.guests,
    arriveAt: b.arriveAt,
    arriveBy: b.arriveBy,
    leaveAt: b.leaveAt,
    code: b.code,
    agreedPrice: b.agreedPrice,
    depositAmount: b.depositAmount,
    payAtDesk: b.payAtDesk,
    savedAt: now,
  };
}

/** Adds or replaces a pass, newest first, dropping passes 30 days past their stay and any beyond MAX_PASSES. */
export function upsertPass(passes: SavedPass[], pass: SavedPass, now: number): SavedPass[] {
  return prune([pass, ...passes.filter((p) => p.id !== pass.id)], now);
}

export function prune(passes: SavedPass[], now: number): SavedPass[] {
  return passes
    .filter((p) => leaveTime(p) + KEEP_AFTER_STAY_MS > now)
    .sort((a, b) => b.savedAt - a.savedAt)
    .slice(0, MAX_PASSES);
}

/** leaveAt is India time without an offset. */
function leaveTime(p: SavedPass): number {
  return new Date(`${p.leaveAt}+05:30`).getTime();
}

export function parsePasses(raw: string | null): SavedPass[] {
  if (!raw) return [];
  try {
    const value = JSON.parse(raw) as unknown;
    return Array.isArray(value) ? (value as SavedPass[]).filter((p) => typeof p?.id === "string") : [];
  } catch {
    return [];
  }
}

/**
 * A saved pass as the pass screen's BookingView, for showing it with no network: the same rows and code, and no
 * actions (cancelling or disputing needs the server).
 */
export function asBookingView(p: SavedPass): BookingView {
  return {
    id: p.id,
    sessionId: p.sessionId,
    status: p.status,
    hotel: { id: "", slug: "", photo: null, ...p.hotel },
    roomType: p.roomType,
    guestName: p.guestName,
    phone: null,
    guests: p.guests,
    arriveAt: p.arriveAt,
    arriveBy: p.arriveBy,
    leaveAt: p.leaveAt,
    agreedPrice: p.agreedPrice,
    depositAmount: p.depositAmount,
    payAtDesk: p.payAtDesk,
    code: p.code,
    payBy: p.arriveAt,
    confirmBy: null,
    refund: null,
    // Nullable in the API (BookingView.nextBest); the generated type loses that on a $ref.
    nextBest: null as unknown as BookingView["nextBest"],
    refundedFrom: null,
    disputeReason: null,
    actions: { cancel: false, turnedAway: false, didArrive: false },
    alternatives: [],
  };
}
