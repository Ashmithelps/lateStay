"use client";

import { useQuery } from "@tanstack/react-query";
import { api, type BookingView, type SearchQuery } from "./api";

export function useSearchResults(query: SearchQuery) {
  return useQuery({
    queryKey: ["search", query],
    queryFn: () => api.search(query),
    // Each search records a stay request: don't repeat it on focus or remount.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    retry: 1,
  });
}

export function useHotel(slug: string, stayRequestId: string | undefined) {
  return useQuery({
    queryKey: ["hotel", slug, stayRequestId ?? null],
    queryFn: () => api.hotel(slug, stayRequestId),
    retry: 1,
  });
}

export function useNegotiation(id: string) {
  return useQuery({
    queryKey: ["negotiation", id],
    queryFn: () => api.negotiation(id),
    refetchOnWindowFocus: false,
    retry: 1,
  });
}

export function useBookingPreview(sessionId: string) {
  return useQuery({
    queryKey: ["booking-preview", sessionId],
    queryFn: () => api.bookingPreview(sessionId),
    refetchOnWindowFocus: false,
    retry: 1,
  });
}

/** Statuses that are still moving on their own, so the pass polls for them. */
export function isWaiting(booking: BookingView): boolean {
  return (
    booking.status === "PENDING_PAYMENT" ||
    booking.status === "DEPOSIT_PAID" ||
    booking.refund?.status === "PENDING"
  );
}

export function useBooking(id: string) {
  return useQuery({
    queryKey: ["booking", id],
    queryFn: () => api.booking(id),
    refetchInterval: (query) => (query.state.data && isWaiting(query.state.data) ? 5000 : false),
    retry: 1,
  });
}
