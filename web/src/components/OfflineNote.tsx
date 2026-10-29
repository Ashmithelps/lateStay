"use client";

import { WifiOff } from "lucide-react";
import { useOnline } from "@/lib/online";
import { cx } from "./ui";

/** A quiet line shown only while offline, e.g. "You're offline. Showing your last search." */
export function OfflineNote({ children, className }: { children: React.ReactNode; className?: string }) {
  const online = useOnline();
  if (online) return null;
  return (
    <p role="status" className={cx("flex items-center gap-2 rounded-control bg-soft px-3 py-2 text-14 text-ink", className)}>
      <WifiOff size={20} strokeWidth={1.5} aria-hidden className="shrink-0" />
      <span>{children}</span>
    </p>
  );
}
