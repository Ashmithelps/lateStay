import { request } from "./api";

/*
 * The owner page's API. Hand-written, like admin-api.ts: /api/owner is kept out of the public OpenAPI spec
 * (it shows floor prices). The backend knows the hotel from the httpOnly ls_owner cookie only.
 */

export type OwnerBookingStatus = "AWAITING_CONFIRMATION" | "CONFIRMED" | "CHECKED_IN" | "NO_SHOW" | "COMPLETED" | "DISPUTED";

export type OwnerSummary = {
  hotelName: string;
  town: string;
  tonight: string; // night_of, yyyy-mm-dd
  roomsFree: number | null; // the owner's latest answer for tonight
  roomsAvailable: number; // what LateStay can still sell
  bookings: {
    id: string;
    guestName: string;
    guests: number;
    rooms: number; // 2 for a group's two rooms
    arriveAt: string; // local date-time
    code: string;
    payAtDesk: number; // rupees
    status: OwnerBookingStatus;
    checkedIn: boolean;
  }[];
  earnings: { weekStart: string; thisWeekPaise: number; owedPaise: number; nextPayoutDate: string };
  settlements: {
    id: string;
    periodStart: string;
    periodEnd: string;
    amountPaise: number;
    status: "DUE" | "PAID";
    upiReference: string | null;
    paidAt: string | null;
  }[];
  rooms: {
    id: string;
    name: string;
    listPrice: number;
    floors: {
      id: string;
      slotType: "LATE_NIGHT" | "EARLY_MORNING";
      weekdays: number;
      seasonStart: string | null;
      seasonEnd: string | null;
      minHours: number;
      floorPrice: number;
    }[];
  }[];
  reliability: OwnerReliability;
  reviews: OwnerReviews;
};

export type OwnerReliability = {
  confirmed: number;
  declined: number;
  timedOut: number;
  confirmRatePercent: number | null;
  medianMinutesToConfirm: number | null;
  rescues: number;
  upheldDisputes: number;
  score: number;
  isNew: boolean;
  pausedAt: string | null;
};

export type OwnerReviews = {
  rating: { value: number | null; count: number };
  tags: { tag: string; count: number }[];
  recent: { stars: number; tags: string[]; text: string | null; stayedIn: string }[];
};

export type OwnerFloor = OwnerSummary["rooms"][number]["floors"][number];

const json = (body: unknown) => ({ body: JSON.stringify(body) });

export const ownerApi = {
  signIn: (token: string) => request<OwnerSummary>("/api/owner/session", { method: "POST", ...json({ token }) }),
  summary: () => request<OwnerSummary>("/api/owner/summary"),
  roomsTonight: (rooms: number) =>
    request<OwnerSummary>("/api/owner/rooms-tonight", { method: "PUT", ...json({ rooms }) }),
  floorPrice: (id: string, floorPrice: number) =>
    request<OwnerFloor>(`/api/owner/floor-rules/${encodeURIComponent(id)}`, { method: "PUT", ...json({ floorPrice }) }),
  signOut: () => request<void>("/api/owner/logout", { method: "POST" }),
};

const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/** "Late night · every day", "Early morning · Fri, Sat · 20 Dec–5 Jan · 6 h or more" */
export function floorLabel(f: OwnerFloor, formatDate: (d: string) => string): string {
  const parts = [f.slotType === "LATE_NIGHT" ? "Late night" : "Early morning"];
  parts.push(f.weekdays === 127 ? "every day" : DAYS.filter((_, i) => f.weekdays & (1 << i)).join(", "));
  if (f.seasonStart && f.seasonEnd) parts.push(`${formatDate(f.seasonStart)}–${formatDate(f.seasonEnd)}`);
  if (f.minHours > 0) parts.push(`${f.minHours} h or more`);
  return parts.join(" · ");
}
