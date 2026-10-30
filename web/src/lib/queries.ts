"use client";

import { useQuery } from "@tanstack/react-query";
import { analyticsId, firstTouch } from "./attribution";
import {
  api,
  type BookingPreview,
  type BookingView,
  type HotelDetail,
  type NegotiationView,
  type SearchQuery,
  type SearchResponse,
} from "./api";

/*
 * Each hook takes the data its page fetched on the server (if any), so the first render already has it and the
 * LCP photo is in the HTML instead of waiting for the JavaScript to fetch it.
 */

/** Server-fetched results apply only to the query they were fetched for. */
export type InitialSearch = { query: SearchQuery; data: SearchResponse };

export function useSearchResults(query: SearchQuery, initial?: InitialSearch) {
  const matches = initial !== undefined && JSON.stringify(initial.query) === JSON.stringify(query);
  return useQuery({
    queryKey: ["search", query],
    // Searches made here carry this browser's first-touch UTM tags and analytics id (not part of the cache key).
    queryFn: () => {
      const touch = firstTouch();
      return api.search({
        ...query,
        utm_source: touch.utmSource ?? undefined,
        utm_medium: touch.utmMedium ?? undefined,
        utm_campaign: touch.utmCampaign ?? undefined,
        aid: analyticsId(),
      });
    },
    initialData: matches ? initial.data : undefined,
    // Each search records a stay request: don't repeat it on focus or remount.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    retry: 1,
  });
}

export function useHotel(slug: string, stayRequestId: string | undefined, initial?: HotelDetail) {
  return useQuery({
    queryKey: ["hotel", slug, stayRequestId ?? null],
    queryFn: () => api.hotel(slug, stayRequestId),
    initialData: initial,
    retry: 1,
  });
}

export function useNegotiation(id: string, initial?: NegotiationView) {
  return useQuery({
    queryKey: ["negotiation", id],
    queryFn: () => api.negotiation(id),
    initialData: initial,
    refetchOnWindowFocus: false,
    retry: 1,
  });
}

export function useBookingPreview(sessionId: string, initial?: BookingPreview) {
  return useQuery({
    queryKey: ["booking-preview", sessionId],
    queryFn: () => api.bookingPreview(sessionId),
    initialData: initial,
    refetchOnWindowFocus: false,
    retry: 1,
  });
}

/** Statuses that are still moving on their own, so the pass polls for them. */
export function isWaiting(booking: BookingView): boolean {
  return (
    booking.status === "PENDING_PAYMENT" ||
    booking.status === "DEPOSIT_PAID" ||
    booking.status === "DISPUTED" ||
    booking.refund?.status === "PENDING"
  );
}

export function useBooking(id: string, initial?: BookingView) {
  return useQuery({
    queryKey: ["booking", id],
    queryFn: () => api.booking(id),
    initialData: initial,
    refetchInterval: (query) => (query.state.data && isWaiting(query.state.data) ? 5000 : false),
    retry: 1,
  });
}
