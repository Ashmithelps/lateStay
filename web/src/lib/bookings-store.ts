import { useSyncExternalStore } from "react";

/** No traveller login for the MVP: the device remembers its bookings by id. */
export type SavedBooking = { id: string; sessionId: string; hotelName: string; savedAt: number };

export const BOOKINGS_KEY = "latestay.bookings";
const MAX_SAVED = 20;
const RECENT_MS = 24 * 60 * 60 * 1000;

type Storage = Pick<globalThis.Storage, "getItem" | "setItem">;

function browserStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function readBookings(storage: Storage | null = browserStorage()): SavedBooking[] {
  try {
    const parsed: unknown = JSON.parse(storage?.getItem(BOOKINGS_KEY) ?? "[]");
    return Array.isArray(parsed)
      ? parsed.filter(
          (b): b is SavedBooking =>
            typeof b?.id === "string" && typeof b?.sessionId === "string" && typeof b?.savedAt === "number",
        )
      : [];
  } catch {
    return [];
  }
}

/** Newest first; saving a booking again moves it to the front. */
export function saveBooking(booking: SavedBooking, storage: Storage | null = browserStorage()): SavedBooking[] {
  const next = [booking, ...readBookings(storage).filter((b) => b.id !== booking.id)].slice(0, MAX_SAVED);
  try {
    storage?.setItem(BOOKINGS_KEY, JSON.stringify(next));
  } catch {
    // storage full or blocked: the pass link still works
  }
  listeners.forEach((l) => l());
  return next;
}

/** The newest booking saved in the last day, if any. */
export function latestBooking(bookings: SavedBooking[], now = Date.now()): SavedBooking | null {
  const latest = bookings[0];
  return latest && now - latest.savedAt < RECENT_MS ? latest : null;
}

const listeners = new Set<() => void>();
let cache: { raw: string | null; value: SavedBooking | null } = { raw: null, value: null };

function snapshot(): SavedBooking | null {
  const raw = browserStorage()?.getItem(BOOKINGS_KEY) ?? null;
  if (raw !== cache.raw) cache = { raw, value: latestBooking(readBookings()) };
  return cache.value;
}

export function useLatestBooking(): SavedBooking | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      window.addEventListener("storage", listener);
      return () => {
        listeners.delete(listener);
        window.removeEventListener("storage", listener);
      };
    },
    snapshot,
    () => null,
  );
}
