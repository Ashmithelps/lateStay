/**
 * "I need a room now": the stay a single tap searches for. Times are India Standard Time.
 *
 * - Arrive: now, rounded up to the next quarter hour (an exact quarter hour stays).
 * - Leave: 13:00 that day for an early-morning arrival (04:00-09:00), otherwise 09:00 the next morning.
 * - Outside the arrival windows the backend accepts (21:00-03:00, 04:00-09:00) it uses the next window's start:
 *   21:00 during the day, 04:00 between 03:00 and 04:00.
 * - 2 guests. Everything stays editable in the search pill.
 */

export const ROOM_NOW_GUESTS = 2;
/** Results this close come first, under their own heading. */
export const NEAR_KM = 3;

export type RoomNow = {
  arrive: string; // HH:mm
  leave: string; // HH:mm
  guests: number;
  /** Set when the arrival had to move to the next window: the time late check-in opens. */
  opensAt?: string;
};

const IST_OFFSET_MINUTES = 330;

function hhmm(minutes: number): string {
  const m = ((minutes % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}

export function roomNow(now: Date = new Date()): RoomNow {
  const shifted = new Date(now.getTime() + IST_OFFSET_MINUTES * 60_000);
  const minutes = shifted.getUTCHours() * 60 + shifted.getUTCMinutes() + (shifted.getUTCSeconds() > 0 ? 1 : 0);
  const rounded = (Math.ceil(minutes / 15) * 15) % 1440;

  const lateNight = rounded >= 21 * 60 || rounded <= 3 * 60;
  const earlyMorning = rounded >= 4 * 60 && rounded <= 9 * 60;
  if (lateNight) return { arrive: hhmm(rounded), leave: "09:00", guests: ROOM_NOW_GUESTS };
  if (earlyMorning) return { arrive: hhmm(rounded), leave: "13:00", guests: ROOM_NOW_GUESTS };
  if (rounded < 4 * 60) return { arrive: "04:00", leave: "13:00", guests: ROOM_NOW_GUESTS, opensAt: "04:00" };
  return { arrive: "21:00", leave: "09:00", guests: ROOM_NOW_GUESTS, opensAt: "21:00" };
}

/** Splits distance-sorted results into "within 3 km" and "further away", keeping their order. */
export function nearFirst<T extends { distanceKm: number }>(results: T[]): { near: T[]; further: T[] } {
  const sorted = [...results].sort((a, b) => a.distanceKm - b.distanceKm);
  return { near: sorted.filter((r) => r.distanceKm <= NEAR_KM), further: sorted.filter((r) => r.distanceKm > NEAR_KM) };
}
