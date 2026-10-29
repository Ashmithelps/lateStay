import { useSyncExternalStore } from "react";

export { FILTER_CODES, readSearch, searchApiQuery, writeSearch, type SearchState } from "./search-params";

const LAST_SEARCH_KEY = "latestay:last-search";

export function rememberSearch(query: string) {
  try {
    sessionStorage.setItem(LAST_SEARCH_KEY, query);
  } catch {
    // storage unavailable
  }
}

/** The results URL to go back to; "/" on the server and before any search. */
export function useLastSearchHref(): string {
  return useSyncExternalStore(
    () => () => {},
    lastSearchHref,
    () => "/",
  );
}

export function lastSearchHref(): string {
  try {
    const query = sessionStorage.getItem(LAST_SEARCH_KEY);
    return query ? `/?${query}` : "/";
  } catch {
    return "/";
  }
}
