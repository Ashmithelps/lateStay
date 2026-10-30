import { HelpCircle, Moon, Ticket, type LucideIcon } from "lucide-react";

export type TabId = "tonight" | "bookings" | "help";

export const TABS: { id: TabId; label: string; href: string; icon: LucideIcon }[] = [
  { id: "tonight", label: "Tonight", href: "/", icon: Moon },
  { id: "bookings", label: "Bookings", href: "/app/bookings", icon: Ticket },
  { id: "help", label: "Help", href: "/app/help", icon: HelpCircle },
];

/** Which tab a page belongs to; null for pages that hide the tab bar (hotel, chat, booking, shared stay...). */
export function tabFor(pathname: string): TabId | null {
  if (pathname === "/") return "tonight";
  if (pathname === "/app/bookings" || pathname.startsWith("/pass/")) return "bookings";
  if (pathname === "/app/help") return "help";
  if (pathname === "/~offline") return "bookings";
  return null;
}

/** The tab bar shows only in the app, and only on the tabs' own pages. Never on the website. */
export function tabBarVisible(native: boolean, pathname: string): boolean {
  return native && tabFor(pathname) !== null;
}
