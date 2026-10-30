const rupees = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });

/** ₹1,300 */
export function formatPrice(amount: number): string {
  return rupees.format(amount);
}

/** "23:30", "23:30:00" or "2026-10-28T23:30" -> "11:30 pm" */
export function formatTime(value: string): string {
  const time = value.includes("T") ? value.split("T")[1] : value;
  const [h, m] = time.split(":").map(Number);
  const suffix = h >= 12 ? "pm" : "am";
  const hour = h % 12 === 0 ? 12 : h % 12;
  return `${hour}:${String(m).padStart(2, "0")} ${suffix}`;
}

/** 0.24 -> "240 m away", 1.5 -> "1.5 km away" */
export function formatDistance(km: number): string {
  if (km < 1) return `${Math.max(10, Math.round((km * 1000) / 10) * 10)} m away`;
  return `${km.toFixed(1)} km away`;
}

export function roomsFreeLabel(rooms: number): string {
  return rooms === 1 ? "1 room free tonight" : `${rooms} rooms free tonight`;
}

const dayFormat = new Intl.DateTimeFormat("en-IN", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });

/** A local date-time from the API ("2026-11-11T23:30:00") -> "Wed, 11 Nov" */
export function formatDay(localDateTime: string): string {
  const [y, m, d] = localDateTime.split("T")[0].split("-").map(Number);
  return dayFormat.format(new Date(Date.UTC(y, m - 1, d)));
}

const istTime = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone: "Asia/Kolkata" });

/** An instant ("2026-11-11T17:05:00Z") -> its India time, "10:35 pm" */
export function formatInstantTime(instant: string): string {
  return formatTime(istTime.format(new Date(instant)));
}

export function guestsLabel(guests: number): string {
  return guests === 1 ? "1 guest" : `${guests} guests`;
}

const paiseFormat = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 0, maximumFractionDigits: 2 });

/** Ledger amounts are in paise: 10000 -> "₹100", 2550 -> "₹25.5" shown as "₹25.50" */
export function formatPaise(paise: number): string {
  const text = paiseFormat.format(paise / 100);
  return /\.\d$/.test(text) ? `${text}0` : text;
}

const dateFormat = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "UTC" });

/** "2026-11-09" -> "9 Nov" */
export function formatDate(isoDate: string): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  return dateFormat.format(new Date(Date.UTC(y, m - 1, d)));
}

/** "2026-11-16" -> "Mon, 16 Nov" */
export function formatWeekday(isoDate: string): string {
  return formatDay(isoDate);
}

/** A room option's name: "Deluxe", or "2 rooms: Twin + Family" for a group. */
export function roomOptionLabel(option: { name: string; rooms: number }): string {
  return option.rooms > 1 ? `${option.rooms} rooms: ${option.name}` : option.name;
}
