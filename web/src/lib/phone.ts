/**
 * Indian mobile numbers: 10 digits starting 6-9, optionally written with +91, 91 or 0, spaces or dashes. Returns
 * the number as +91XXXXXXXXXX, or null. The backend applies the same rule.
 */
export function normaliseIndianMobile(input: string): string | null {
  const match = /^(?:\+?91|0)?([6-9]\d{9})$/.exec(input.replace(/[\s-]/g, ""));
  return match ? `+91${match[1]}` : null;
}

/** +919876543210 -> "+91 98765 43210" */
export function formatIndianMobile(e164: string): string {
  const digits = e164.replace(/^\+91/, "");
  return digits.length === 10 ? `+91 ${digits.slice(0, 5)} ${digits.slice(5)}` : e164;
}
