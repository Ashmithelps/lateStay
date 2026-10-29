import "server-only";

/**
 * Server pages fetch their first data so the HTML already has it. A failure isn't fatal: the client screen
 * fetches again (and shows its own error) if this comes back undefined.
 */
export async function prefetch<T>(request: Promise<T>): Promise<T | undefined> {
  try {
    return await request;
  } catch {
    return undefined;
  }
}

/** Next's searchParams object as URLSearchParams (repeated keys keep their first value). */
export function toSearchParams(raw: Record<string, string | string[] | undefined>): URLSearchParams {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(raw)) {
    const first = Array.isArray(value) ? value[0] : value;
    if (first !== undefined) params.set(key, first);
  }
  return params;
}
