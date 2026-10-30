import type { BookingView } from "@/lib/api";

/** One short line for a saved pass's status (the Bookings tab and the offline screen). */
export function passStatusLabel(status: BookingView["status"]): string {
  switch (status) {
    case "PENDING_PAYMENT":
      return "Not paid yet";
    case "DEPOSIT_PAID":
      return "Waiting for the hotel";
    case "HOTEL_CONFIRMED":
      return "Confirmed";
    case "CHECKED_IN":
      return "Checked in";
    case "COMPLETED":
      return "Stay complete";
    case "NO_SHOW":
    case "DISPUTED":
      return "Being checked";
    default:
      return "Not going ahead";
  }
}
