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

export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    // Content-Type only with a body: on a GET it would make the request "non-simple" and cost a CORS preflight.
    headers: { ...(init?.body ? { "Content-Type": "application/json" } : {}), ...init?.headers },
    cache: "no-store",
  });
  if (!response.ok) {
    let detail = response.statusText;
    try {
      const problem = (await response.json()) as { detail?: string };
      detail = problem.detail ?? detail;
    } catch {
      // not a problem-detail body
    }
    throw new ApiError(response.status, detail);
  }
  return (await response.json()) as T;
}

export const api = {
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
  startNegotiation(stayRequestId: string, roomTypeId: string) {
    return request<NegotiationCreated>("/api/negotiations", {
      method: "POST",
      body: JSON.stringify({ stayRequestId, roomTypeId }),
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
  /** Local backend with the fake gateway only: what Checkout would return for a successful payment. */
  devPay(orderId: string) {
    return request<VerifyPaymentRequest>(`/api/dev/payments/${encodeURIComponent(orderId)}/pay`, { method: "POST" });
  },
};
