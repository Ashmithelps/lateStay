import "server-only";
import { API_URL, type TownPage, type TownSummary } from "./api";

/** Town pages are static and rebuilt hourly: these fetches are cached for that long. */
export const TOWN_REVALIDATE_SECONDS = 3600;

async function get<T>(path: string): Promise<T | null> {
  try {
    const response = await fetch(`${API_URL}${path}`, { next: { revalidate: TOWN_REVALIDATE_SECONDS } });
    if (!response.ok) return null;
    return (await response.json()) as T;
  } catch {
    // The API may not be reachable while building: the pages are then rendered on their first visit.
    return null;
  }
}

export function fetchTowns(): Promise<TownSummary[] | null> {
  return get<TownSummary[]>("/api/towns");
}

export function fetchTown(slug: string): Promise<TownPage | null> {
  return get<TownPage>(`/api/towns/${encodeURIComponent(slug)}`);
}
