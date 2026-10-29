import type { SearchQuery } from "./api";
import type { FilterCode } from "./amenities";
import { DEFAULT_ARRIVE, DEFAULT_LEAVE, fitLeave, stayDates } from "./stay";
import { DEFAULT_TOWN, townById } from "./towns";

/*
 * The home page's search, read from and written to its URL. No React here: the server page uses it too, to
 * fetch the first results before the JavaScript arrives.
 */

/** The search as it lives in the home page URL. */
export type SearchState = {
  place: string; // a town id, or "here" for the device location
  placeLabel: string;
  lat: number;
  lng: number;
  arrive: string; // HH:mm
  leave: string; // HH:mm
  guests: number;
  filters: FilterCode[];
};

export const FILTER_CODES: FilterCode[] = ["heater", "parking", "couple_friendly", "near_bus_stand", "late_check_in"];

export function readSearch(params: URLSearchParams): SearchState {
  const town = townById(params.get("place")) ?? DEFAULT_TOWN;
  const here = params.get("place") === "here" && params.get("lat") && params.get("lng");
  const arrive = params.get("arrive") ?? DEFAULT_ARRIVE;
  const guests = Math.min(8, Math.max(1, Number(params.get("guests") ?? 2) || 2));
  return {
    place: here ? "here" : town.id,
    placeLabel: here ? "Your location" : town.name,
    lat: here ? Number(params.get("lat")) : town.lat,
    lng: here ? Number(params.get("lng")) : town.lng,
    arrive,
    leave: fitLeave(arrive, params.get("leave") ?? DEFAULT_LEAVE),
    guests,
    filters: (params.get("filters") ?? "")
      .split(",")
      .filter((f): f is FilterCode => (FILTER_CODES as string[]).includes(f)),
  };
}

export function writeSearch(state: SearchState): string {
  const params = new URLSearchParams();
  params.set("place", state.place);
  if (state.place === "here") {
    params.set("lat", state.lat.toFixed(5));
    params.set("lng", state.lng.toFixed(5));
  }
  params.set("arrive", state.arrive);
  params.set("leave", state.leave);
  params.set("guests", String(state.guests));
  if (state.filters.length) params.set("filters", state.filters.join(","));
  return params.toString();
}

/** The /api/search query for a search, with tonight's dates worked out in India time. */
export function searchApiQuery(search: SearchState, now: Date = new Date()): SearchQuery {
  const dates = stayDates(search.arrive, search.leave, now);
  return {
    lat: search.lat,
    lng: search.lng,
    arrive: dates.arrive,
    leave: dates.leave,
    guests: search.guests,
    filters: search.filters.length ? search.filters.join(",") : undefined,
  };
}
