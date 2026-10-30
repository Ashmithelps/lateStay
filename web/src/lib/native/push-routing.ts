/** Where tapping a notification goes: its booking's pass (or the pass's review page). Anything else: Tonight. */
const PASS_PATH = /^\/pass\/[0-9a-f-]{36}(\/review)?$/;

export function notificationPath(data: Record<string, unknown> | undefined | null): string {
  const path = typeof data?.path === "string" ? data.path : null;
  if (path && PASS_PATH.test(path)) return path;
  const id = typeof data?.bookingId === "string" ? data.bookingId : null;
  return id && /^[0-9a-f-]{36}$/.test(id) ? `/pass/${id}` : "/";
}

/** Universal Links / App Links: our /pass/* and /stay/* URLs open in the app at that path. */
const DEEP_LINK = /^\/(pass\/[0-9a-f-]{36}(\/review)?|stay\/[A-Za-z0-9_-]{22,64})$/;

export function deepLinkPath(url: string, ourHosts: string[]): string | null {
  try {
    const u = new URL(url);
    if (!ourHosts.includes(u.host)) return null;
    return DEEP_LINK.test(u.pathname) ? u.pathname : null;
  } catch {
    return null;
  }
}
