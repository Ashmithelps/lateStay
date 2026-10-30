import { useSyncExternalStore } from "react";
import { API_URL, ApiError, type BookingStatus } from "./api";

/** Admin types are written by hand: the admin API is left out of the public OpenAPI contract on purpose. */
export type AdminBooking = {
  id: string;
  status: BookingStatus;
  hotelId: string;
  hotelName: string;
  roomType: string;
  guestName: string;
  /** Null once deleted, 90 days after the stay. */
  phone: string | null;
  guests: number;
  arriveAt: string;
  arriveBy: string;
  leaveAt: string;
  agreedPrice: number;
  depositAmount: number;
  payAtDesk: number;
  commissionAmount: number;
  code: string;
  payBy: string;
  confirmBy: string | null;
  paymentStatus: string | null;
  razorpayPaymentId: string | null;
  refundId: string | null;
  createdAt: string;
  updatedAt: string;
  sessionId: string;
  disputeReason: "TRAVELLER_ARRIVED" | "TURNED_AWAY" | null;
  disputedAt: string | null;
  arrivalCheckSentAt: string | null;
  hotelFailures: number;
  /** "Turned away": the hotel is hidden from search until then, unless keepHiddenAt is set. */
  hideUntil: string | null;
  keepHiddenAt: string | null;
};

export type AdminBookingDetail = {
  booking: AdminBooking;
  events: { from: BookingStatus | null; to: BookingStatus; actor: string; at: string }[];
};

export type AdminNegotiation = {
  id: string;
  status: "OPEN" | "AGREED" | "FINAL_OFFER" | "NO_DEAL" | "EXPIRED";
  hotelName: string;
  roomType: string;
  listPrice: number;
  floorPrice: number;
  currentOffer: number;
  agreedPrice: number | null;
  round: number;
  maxRounds: number;
  turns: number;
  arriveAt: string;
  guests: number;
  expiresAt: string;
  updatedAt: string;
  bookingId: string | null;
};

export type AdminTurn = {
  seq: number;
  role: "TRAVELLER" | "BOT";
  text: string;
  intent: string | null;
  decision: string | null;
  validatorResult: string | null;
  latencyMs: number | null;
  at: string;
};

export type AdminHotel = {
  id: string;
  name: string;
  slug: string;
  town: string;
  address: string;
  lat: number;
  lng: number;
  whatsappNumber: string;
  amenities: string[];
  policies: { coupleFriendly: boolean; localIdOk: boolean; checkInNotes: string | null };
  /** All the hotel's rooms (occupancy pricing: rooms free tonight out of these). */
  totalRooms: number;
  active: boolean;
  photos: string[];
};

/** uploaded: false for seed photos (a plain URL). The cover is the first photo. */
export type AdminPhoto = {
  id: number;
  url: string;
  thumbnailUrl: string;
  width: number | null;
  height: number | null;
  sortOrder: number;
  cover: boolean;
  uploaded: boolean;
};

export type RejectedPhoto = { fileName: string; code: string; message: string };

export type AdminRoomType = { id: string; hotelId: string; name: string; listPrice: number; maxGuests: number };

export type SlotType = "LATE_NIGHT" | "EARLY_MORNING";

export type AdminFloorRule = {
  id: string;
  roomTypeId: string;
  slotType: SlotType;
  weekdays: number;
  seasonStart: string | null;
  seasonEnd: string | null;
  floorPrice: number;
  minHours: number;
};

export type FloorRuleBody = Omit<AdminFloorRule, "id" | "roomTypeId">;

export type Tonight = {
  nightOf: string;
  hotels: {
    hotelId: string;
    hotelName: string;
    town: string;
    active: boolean;
    roomsFree: number | null;
    reportedAt: string | null;
    source: string | null;
    /** The report minus active holds and bookings made since. */
    available: number;
    reliabilityFailures: number;
  }[];
};

export type WhatsAppLogEntry = {
  id: number;
  direction: "IN" | "OUT";
  waId: string;
  type: string;
  template: string | null;
  summary: string;
  status: "QUEUED" | "SENT" | "DELIVERED" | "READ" | "FAILED" | "NOT_SENT" | "RECEIVED" | "IGNORED";
  error: string | null;
  bookingId: string | null;
  at: string;
};

// Credentials: HTTP basic, checked by the backend. Kept in sessionStorage, so they last for this tab only.

const CREDENTIALS_KEY = "latestay.admin";
const listeners = new Set<() => void>();

function readCredentials(): string | null {
  try {
    return sessionStorage.getItem(CREDENTIALS_KEY);
  } catch {
    return null;
  }
}

function writeCredentials(value: string | null) {
  try {
    if (value) sessionStorage.setItem(CREDENTIALS_KEY, value);
    else sessionStorage.removeItem(CREDENTIALS_KEY);
  } catch {
    // storage blocked: the admin has to sign in again on reload
  }
  memory = value;
  listeners.forEach((l) => l());
}

let memory: string | null = null;

export function useAdminCredentials(): string | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => readCredentials() ?? memory,
    () => null,
  );
}

function encode(username: string, password: string): string {
  return btoa(String.fromCharCode(...new TextEncoder().encode(`${username}:${password}`)));
}

async function adminRequest<T>(credentials: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_URL}/api/admin${path}`, {
    ...init,
    headers: {
      // FormData (photo uploads) sets its own multipart Content-Type with the boundary.
      ...(init?.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
      Authorization: `Basic ${credentials}`,
      // No WWW-Authenticate comes back, so the browser never shows its own password prompt.
      "X-Requested-With": "fetch",
      ...init?.headers,
    },
    cache: "no-store",
  });
  if (response.status === 401) {
    writeCredentials(null);
    throw new ApiError(401, "Signed out. Sign in again.");
  }
  if (!response.ok) {
    let detail = response.statusText;
    try {
      detail = ((await response.json()) as { detail?: string }).detail ?? detail;
    } catch {
      // not a problem-detail body
    }
    throw new ApiError(response.status, detail);
  }
  // 202 and 204 come back without a body.
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function signIn(username: string, password: string): Promise<void> {
  const credentials = encode(username, password);
  const response = await fetch(`${API_URL}/api/admin/me`, {
    headers: { Authorization: `Basic ${credentials}`, "X-Requested-With": "fetch" },
    cache: "no-store",
  });
  if (response.status === 401) throw new ApiError(401, "That username and password don't match.");
  if (!response.ok) throw new ApiError(response.status, "The backend didn't answer. Is it running?");
  writeCredentials(credentials);
}

export function signOut() {
  writeCredentials(null);
}

const json = (body: unknown) => ({ body: JSON.stringify(body) });

export function adminApi(credentials: string) {
  const call = <T>(path: string, init?: RequestInit) => adminRequest<T>(credentials, path, init);
  return {
    bookings: () => call<AdminBooking[]>("/bookings"),
    confirm: (id: string) => call<AdminBookingDetail>(`/bookings/${id}/confirm`, { method: "POST" }),
    decline: (id: string) => call<AdminBookingDetail>(`/bookings/${id}/decline`, { method: "POST" }),
    settle: (id: string, outcome: "GUEST_STAYED" | "GUEST_DID_NOT_ARRIVE") =>
      call<AdminBookingDetail>(`/bookings/${id}/settle`, { method: "POST", ...json({ outcome }) }),
    rescue: (id: string) => call<AdminBookingDetail>(`/bookings/${id}/rescue`, { method: "POST" }),
    keepHidden: (id: string) => call<AdminBookingDetail>(`/bookings/${id}/keep-hidden`, { method: "POST" }),
    settlements: (status?: "DUE" | "PAID") =>
      call<AdminSettlement[]>(`/settlements${status ? `?status=${status}` : ""}`),
    settlement: (id: string) => call<AdminSettlementDetail>(`/settlements/${id}`),
    markPaid: (id: string, upiReference: string) =>
      call<AdminSettlementDetail>(`/settlements/${id}/paid`, { method: "POST", ...json({ upiReference }) }),
    settlementCsv: (id: string) => adminDownload(credentials, `/settlements/${id}.csv`),
    runSettlements: () => call<{ made: number }>("/settlements/run", { method: "POST" }),
    today: (night?: string) => call<AdminDigest>(`/today${night ? `?night=${night}` : ""}`),
    funnel: (days: 7 | 30, town?: string, utmSource?: string) => {
      const q = new URLSearchParams({ days: String(days) });
      if (town) q.set("town", town);
      if (utmSource) q.set("utmSource", utmSource);
      return call<AdminFunnel>(`/funnel?${q}`);
    },
    reviews: () => call<AdminReview[]>("/reviews"),
    hideReviewText: (id: string) => call<void>(`/reviews/${id}/hide-text`, { method: "POST" }),
    showReviewText: (id: string) => call<void>(`/reviews/${id}/show-text`, { method: "POST" }),
    reliability: () => call<AdminReliabilityRow[]>("/reliability"),
    reliabilityReviewed: (hotelId: string) => call<void>(`/hotels/${hotelId}/reliability-reviewed`, { method: "POST" }),
    seasons: () => call<AdminSeason[]>("/seasons"),
    createSeason: (body: Omit<AdminSeason, "id">) => call<AdminSeason>("/seasons", { method: "POST", ...json(body) }),
    deleteSeason: (id: string) => call<void>(`/seasons/${id}`, { method: "DELETE" }),
    promoCodes: () => call<AdminPromo[]>("/promo-codes"),
    createPromo: (body: { code: string; discountPaise: number; validFrom: string; validUntil: string; maxUses: number }) =>
      call<AdminPromo>("/promo-codes", { method: "POST", ...json(body) }),
    deactivatePromo: (id: string) => call<AdminPromo>(`/promo-codes/${id}/deactivate`, { method: "POST" }),
    checkIn: (id: string, code: string) =>
      call<AdminBookingDetail>(`/bookings/${id}/check-in`, { method: "POST", ...json({ code }) }),
    negotiations: (state: "live" | "recent") => call<AdminNegotiation[]>(`/negotiations?state=${state}`),
    transcript: (id: string) =>
      call<{ negotiation: AdminNegotiation; turns: AdminTurn[] }>(`/negotiations/${id}`),
    hotels: () => call<AdminHotel[]>("/hotels"),
    // Photos are left out: they're managed with the photo calls below.
    updateHotel: (id: string, body: Omit<AdminHotel, "id" | "photos">) =>
      call<AdminHotel>(`/hotels/${id}`, { method: "PUT", ...json(body) }),
    photos: (hotelId: string) => call<AdminPhoto[]>(`/hotels/${hotelId}/photos`),
    uploadPhotos: (hotelId: string, files: File[]) => {
      const form = new FormData();
      files.forEach((f) => form.append("files", f, f.name));
      return call<{ photos: AdminPhoto[]; rejected: RejectedPhoto[] }>(`/hotels/${hotelId}/photos`, {
        method: "POST",
        body: form,
      });
    },
    reorderPhotos: (hotelId: string, ids: number[]) =>
      call<AdminPhoto[]>(`/hotels/${hotelId}/photos/order`, { method: "PUT", ...json({ ids }) }),
    coverPhoto: (hotelId: string, id: number) =>
      call<AdminPhoto[]>(`/hotels/${hotelId}/photos/${id}/cover`, { method: "POST" }),
    deletePhoto: (hotelId: string, id: number) =>
      call<AdminPhoto[]>(`/hotels/${hotelId}/photos/${id}`, { method: "DELETE" }),
    roomTypes: (hotelId: string) => call<AdminRoomType[]>(`/hotels/${hotelId}/room-types`),
    createRoomType: (hotelId: string, body: Omit<AdminRoomType, "id" | "hotelId">) =>
      call<AdminRoomType>(`/hotels/${hotelId}/room-types`, { method: "POST", ...json(body) }),
    updateRoomType: (hotelId: string, id: string, body: Omit<AdminRoomType, "id" | "hotelId">) =>
      call<AdminRoomType>(`/hotels/${hotelId}/room-types/${id}`, { method: "PUT", ...json(body) }),
    floorRules: (roomTypeId: string) => call<AdminFloorRule[]>(`/room-types/${roomTypeId}/floor-rules`),
    createFloorRule: (roomTypeId: string, body: FloorRuleBody) =>
      call<AdminFloorRule>(`/room-types/${roomTypeId}/floor-rules`, { method: "POST", ...json(body) }),
    updateFloorRule: (roomTypeId: string, id: string, body: FloorRuleBody) =>
      call<AdminFloorRule>(`/room-types/${roomTypeId}/floor-rules/${id}`, { method: "PUT", ...json(body) }),
    deleteFloorRule: (roomTypeId: string, id: string) =>
      call<void>(`/room-types/${roomTypeId}/floor-rules/${id}`, { method: "DELETE" }),
    tonight: () => call<Tonight>("/availability"),
    whatsappLog: (hotelId: string) => call<WhatsAppLogEntry[]>(`/hotels/${hotelId}/whatsapp`),
    pingAvailability: (hotelId: string) =>
      call<void>(`/hotels/${hotelId}/whatsapp/availability-ping`, { method: "POST" }),
    setRoomsFree: (hotelId: string, nightOf: string, roomsFree: number) =>
      call<unknown>(`/hotels/${hotelId}/availability/${nightOf}`, { method: "PUT", ...json({ roomsFree }) }),
  };
}

export type AdminApi = ReturnType<typeof adminApi>;

/** A review, with its text even when hidden (backend review.ReviewDtos.AdminReview). */
export type AdminReview = {
  id: string;
  bookingId: string;
  hotelId: string;
  hotelName: string;
  stars: number;
  tags: string[];
  text: string | null;
  textHidden: boolean;
  createdAt: string;
};

/** The last 30 days at one hotel (backend reliability.HotelReliability). */
export type HotelReliability = {
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

export type AdminReliabilityRow = { hotelId: string; name: string; town: string; active: boolean; reliability: HotelReliability };

/** A season in one town: nights from startsOn to endsOn (inclusive) open at multiplierPercent. */
export type AdminSeason = { id: string; town: string; startsOn: string; endsOn: string; multiplierPercent: number; label: string };

/** The morning digest's numbers for one night (backend digest.Digest). Money in paise. */
export type AdminDigest = {
  night: string;
  from: string;
  to: string;
  bookings: number;
  checkedIn: number;
  completed: number;
  noShows: number;
  rescues: number;
  openDisputes: number;
  refunds: number;
  refundsPaise: number;
  commissionPaise: number;
  owedToHotelsPaise: number;
  noAnswer: string[];
  llmCostPaise: number;
  llmCostMicroUsd: number;
};

/** Visitors per funnel step (backend digest.Funnel.Report). LLM cost in micro-dollars. */
export type AdminFunnel = {
  days: number;
  town: string | null;
  utmSource: string | null;
  steps: { name: string; visitors: number }[];
  llmCostMicroUsd: number;
  llmCostPaise: number;
  completedBookings: number;
  llmCostPerBookingMicroUsd: number | null;
  llmCostPerBookingPaise: number | null;
  towns: string[];
  sources: string[];
};

export type AdminPromo = {
  id: string;
  code: string;
  discountPaise: number;
  validFrom: string;
  validUntil: string;
  maxUses: number;
  active: boolean;
  uses: number;
  createdAt: string;
};

/** A weekly payout to a hotel. Amounts in paise. */
export type AdminSettlement = {
  id: string;
  hotelId: string;
  hotelName: string;
  periodStart: string;
  periodEnd: string;
  amountPaise: number;
  status: "DUE" | "PAID";
  upiReference: string | null;
  paidAt: string | null;
  createdAt: string;
};

export type SettlementLine = {
  entryId: string;
  type: "DEPOSIT_COLLECTED" | "COMMISSION" | "REFUND" | "PAYOUT" | "ADJUSTMENT";
  amountPaise: number;
  note: string | null;
  createdAt: string;
  bookingCode: string | null;
  nightOf: string | null;
  guestName: string | null;
};

export type AdminSettlementDetail = { settlement: AdminSettlement; lines: SettlementLine[] };

/** A file from the admin API (basic auth, so not a plain link): fetched, then saved through a blob URL. */
async function adminDownload(credentials: string, path: string): Promise<void> {
  const response = await fetch(`${API_URL}/api/admin${path}`, {
    headers: { Authorization: `Basic ${credentials}`, "X-Requested-With": "fetch" },
    cache: "no-store",
  });
  if (!response.ok) throw new ApiError(response.status, "Couldn't download the file.");
  const name = /filename="([^"]+)"/.exec(response.headers.get("Content-Disposition") ?? "")?.[1] ?? "download.csv";
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}
