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
  phone: string;
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
  rating: number | null;
  active: boolean;
  photos: string[];
};

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
      "Content-Type": "application/json",
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
    checkIn: (id: string, code: string) =>
      call<AdminBookingDetail>(`/bookings/${id}/check-in`, { method: "POST", ...json({ code }) }),
    negotiations: (state: "live" | "recent") => call<AdminNegotiation[]>(`/negotiations?state=${state}`),
    transcript: (id: string) =>
      call<{ negotiation: AdminNegotiation; turns: AdminTurn[] }>(`/negotiations/${id}`),
    hotels: () => call<AdminHotel[]>("/hotels"),
    updateHotel: (id: string, body: Omit<AdminHotel, "id">) =>
      call<AdminHotel>(`/hotels/${id}`, { method: "PUT", ...json(body) }),
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
