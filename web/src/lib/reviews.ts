/** Review tags, in the order the form shows them. Mirrors the backend's ReviewTag. */
export const REVIEW_TAGS = [
  { id: "CLEAN", label: "Clean" },
  { id: "WARM", label: "Warm" },
  { id: "QUIET", label: "Quiet" },
  { id: "FRIENDLY_DESK", label: "Friendly desk" },
  { id: "AS_SHOWN", label: "As shown" },
] as const;

export type ReviewTagId = (typeof REVIEW_TAGS)[number]["id"];

export const MAX_REVIEW_TEXT = 300;

export function tagLabel(id: string): string {
  return REVIEW_TAGS.find((t) => t.id === id)?.label ?? id;
}

/** Ten or more digits with spaces, dots, brackets or dashes between: the backend refuses these as phone numbers. */
const PHONE = /\d(?:[\s.()-]*\d){9,}/;

/** What's wrong with the review text before sending it, or null. */
export function reviewTextProblem(text: string): string | null {
  const trimmed = text.trim();
  if ([...trimmed].length > MAX_REVIEW_TEXT) return `Please keep it to ${MAX_REVIEW_TEXT} characters.`;
  if (PHONE.test(trimmed)) return "Please leave out phone numbers.";
  return null;
}

/** "2026-11" -> "November 2026". */
export function formatStayMonth(yearMonth: string): string {
  const [year, month] = yearMonth.split("-").map(Number);
  return new Intl.DateTimeFormat("en-IN", { month: "long", year: "numeric", timeZone: "UTC" }).format(
    new Date(Date.UTC(year, month - 1, 1)),
  );
}

/** "4.4 · 12 reviews", or "New" until a hotel has 3. */
export function ratingLabel(rating: number | null, count: number): string {
  if (rating === null) return "New";
  return `${rating.toFixed(1)} · ${count} ${count === 1 ? "review" : "reviews"}`;
}
