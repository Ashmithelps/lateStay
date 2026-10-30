import type { components, paths } from "./api/schema";

type Schemas = components["schemas"];
export type SearchResponse = Schemas["SearchResponse"];
export type HotelResult = Schemas["HotelResult"];
export type RoomOption = Schemas["RoomOption"];
export type HotelDetail = Schemas["HotelDetail"];
export type NegotiationView = Schemas["NegotiationView"];
export type NegotiationCreated = Schemas["NegotiationCreated"];
export type ConversationReply = Schemas["ConversationReply"];
export type SessionStatus = NegotiationView["status"];
export type SearchQuery = paths["/api/search"]["get"]["parameters"]["query"];
export type BookingPreview = Schemas["BookingPreview"];
export type BookingCreated = Schemas["BookingCreated"];
export type Checkout = NonNullable<Schemas["Checkout"]>;
export type BookingView = Schemas["BookingView"];
export type BookingStatus = BookingView["status"];
export type CreateBookingRequest = Schemas["CreateBookingRequest"];
export type VerifyPaymentRequest = Schemas["VerifyPaymentRequest"];
export type NextBest = Schemas["NextBest"];
export type PromoPreview = Schemas["PromoPreview"];
export type ReviewState = Schemas["ReviewState"];
export type ReviewBody = Schemas["ReviewBody"];
export type MyReview = Schemas["MyReview"];
export type ShareLink = Schemas["ShareLink"];
export type SharedStay = Schemas["SharedStay"];
export type TownSummary = Schemas["TownSummary"];
export type TownPage = Schemas["TownPage"];

/** A problem-detail body's machine-readable code (ROOM_GONE, WRONG_CODE, PHONE_NOT_VERIFIED, ...). */
export type ProblemCode = string | undefined;

export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: ProblemCode,
    readonly attemptsLeft?: number,
  ) {
    super(message);
  }
}

/** A JSON call to the backend (cookies included). Also used by the owner page's hand-written API. */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    // Content-Type only with a body: on a GET it would make the request "non-simple" and cost a CORS preflight.
    headers: { ...(init?.body ? { "Content-Type": "application/json" } : {}), ...init?.headers },
    cache: "no-store",
    // The API sets and reads the "phone verified on this device" cookie (same site: latestay.in / api.latestay.in).
    credentials: "include",
  });
  if (!response.ok) {
    let detail = response.statusText;
    let code: ProblemCode;
    let attemptsLeft: number | undefined;
    try {
      const problem = (await response.json()) as { detail?: string; code?: string; attemptsLeft?: number };
      detail = problem.detail ?? detail;
      code = problem.code;
      attemptsLeft = problem.attemptsLeft;
    } catch {
      // not a problem-detail body
    }
    throw new ApiError(response.status, detail, code, attemptsLeft);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export const api = {
  /** The server-rendered first search can't see this browser's tags: they're added once, afterwards. */
  attributeStay(stayRequestId: string, body: { utmSource: string | null; utmMedium: string | null; utmCampaign: string | null; analyticsId: string }) {
    return request<void>(`/api/stay-requests/${encodeURIComponent(stayRequestId)}/attribution`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  promo(sessionId: string, code: string) {
    return request<PromoPreview>(
      `/api/negotiations/${encodeURIComponent(sessionId)}/promo?code=${encodeURIComponent(code)}`,
    );
  },
  search(query: SearchQuery) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== "") params.set(key, String(value));
    }
    return request<SearchResponse>(`/api/search?${params}`);
  },
  hotel(slug: string, stayRequestId?: string) {
    const query = stayRequestId ? `?stayRequestId=${encodeURIComponent(stayRequestId)}` : "";
    return request<HotelDetail>(`/api/hotels/${encodeURIComponent(slug)}${query}`);
  },
  negotiation(id: string) {
    return request<NegotiationView>(`/api/negotiations/${encodeURIComponent(id)}`);
  },
  /** One room, or a group's two rooms negotiated together. */
  startNegotiation(stayRequestId: string, roomTypeIds: string[]) {
    return request<NegotiationCreated>("/api/negotiations", {
      method: "POST",
      body: JSON.stringify({ stayRequestId, roomTypeIds }),
    });
  },
  sendMessage(id: string, text: string) {
    return request<ConversationReply>(`/api/negotiations/${encodeURIComponent(id)}/messages`, {
      method: "POST",
      body: JSON.stringify({ text }),
    });
  },
  accept(id: string) {
    return request<ConversationReply>(`/api/negotiations/${encodeURIComponent(id)}/accept`, { method: "POST" });
  },
  bookingPreview(sessionId: string) {
    return request<BookingPreview>(`/api/negotiations/${encodeURIComponent(sessionId)}/booking-preview`);
  },
  createBooking(body: CreateBookingRequest) {
    return request<BookingCreated>("/api/bookings", { method: "POST", body: JSON.stringify(body) });
  },
  booking(id: string) {
    return request<BookingView>(`/api/bookings/${encodeURIComponent(id)}`);
  },
  verifyPayment(id: string, body: VerifyPaymentRequest) {
    return request<BookingView>(`/api/bookings/${encodeURIComponent(id)}/verify`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  cancelBooking(id: string) {
    return request<BookingView>(`/api/bookings/${encodeURIComponent(id)}/cancel`, { method: "POST" });
  },
  travellerArrived(id: string) {
    return request<BookingView>(`/api/bookings/${encodeURIComponent(id)}/arrived`, { method: "POST" });
  },
  turnedAway(id: string) {
    return request<BookingView>(`/api/bookings/${encodeURIComponent(id)}/turned-away`, { method: "POST" });
  },
  sendCode(phone: string) {
    return request<{ id: string; expiresAt: string; resendAfterSeconds: number; sent: boolean }>(
      "/api/phone-verifications",
      { method: "POST", body: JSON.stringify({ phone }) },
    );
  },
  checkCode(id: string, code: string) {
    return request<{ verified: boolean }>(`/api/phone-verifications/${encodeURIComponent(id)}/check`, {
      method: "POST",
      body: JSON.stringify({ code }),
    });
  },
  phoneStatus(phone: string) {
    return request<{ verified: boolean }>(`/api/phone-verifications/status?phone=${encodeURIComponent(phone)}`);
  },
  reviewState(bookingId: string) {
    return request<ReviewState>(`/api/bookings/${encodeURIComponent(bookingId)}/review`);
  },
  submitReview(bookingId: string, body: ReviewBody) {
    return request<MyReview>(`/api/bookings/${encodeURIComponent(bookingId)}/review`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  /** The pass's live share link, or null. */
  shareLink(bookingId: string) {
    return request<ShareLink | undefined>(`/api/bookings/${encodeURIComponent(bookingId)}/share`).then((l) => l ?? null);
  },
  createShareLink(bookingId: string) {
    return request<ShareLink>(`/api/bookings/${encodeURIComponent(bookingId)}/share`, { method: "POST" });
  },
  turnOffShareLink(bookingId: string) {
    return request<void>(`/api/bookings/${encodeURIComponent(bookingId)}/share`, { method: "DELETE" });
  },
  /** Local backend with the fake gateway only: what Checkout would return for a successful payment. */
  devPay(orderId: string) {
    return request<VerifyPaymentRequest>(`/api/dev/payments/${encodeURIComponent(orderId)}/pay`, { method: "POST" });
  },
};
