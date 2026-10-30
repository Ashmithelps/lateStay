"use client";

import { ChevronRight, Ticket } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { ButtonLink } from "@/components/Button";
import { cx, focusRing } from "@/components/ui";
import { Wordmark } from "@/components/Wordmark";
import { formatDay, formatTime } from "@/lib/format";
import { readSavedPasses } from "@/lib/native/plugins";
import { passStatusLabel } from "@/lib/native/pass-status";
import type { SavedPass } from "@/lib/native/saved-passes";

/** The Bookings tab: passes saved on this phone, newest first. They open with no connection too. */
export function BookingsScreen() {
  const [passes, setPasses] = useState<SavedPass[] | null>(null);
  useEffect(() => {
    void readSavedPasses().then(setPasses);
  }, []);

  return (
    <>
      <header className="sticky top-0 z-[1001] border-b border-hairline bg-canvas">
        <div className="mx-auto flex min-h-16 max-w-[640px] items-center px-4">
          <Wordmark />
        </div>
      </header>
      <main className="mx-auto max-w-[640px] px-4 pb-12 pt-6">
        <h1 className="text-26 font-semibold text-ink">Bookings</h1>
        <p className="mt-1 text-14 text-ink-secondary">Saved on this phone, so your code works without a connection.</p>
        {passes === null ? (
          <div className="mt-6 h-24 animate-pulse rounded-card bg-soft" aria-busy="true" />
        ) : passes.length === 0 ? (
          <div className="mt-12 text-center">
            <Ticket size={24} strokeWidth={1.5} aria-hidden className="mx-auto text-ink-secondary" />
            <p className="mt-3 text-16 text-ink">No bookings on this phone yet</p>
            <p className="mt-1 text-14 text-ink-secondary">Your pass appears here as soon as you book.</p>
            <ButtonLink href="/" variant="outline" className="mt-6">
              Find a room tonight
            </ButtonLink>
          </div>
        ) : (
          <ul className="mt-6 divide-y divide-hairline border-y border-hairline">
            {passes.map((p) => (
              <li key={p.id}>
                <Link href={`/pass/${p.id}`} className={cx("flex min-h-16 items-center gap-4 py-4 hover:bg-soft", focusRing)}>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-16 font-semibold text-ink">{p.hotel.name}</span>
                    <span className="block text-14 text-ink-secondary">
                      Arrive by {formatTime(p.arriveBy)}, {formatDay(p.arriveBy)}
                    </span>
                    <span className="block text-14 font-medium text-ink">{passStatusLabel(p.status)}</span>
                  </span>
                  <ChevronRight size={20} strokeWidth={1.5} aria-hidden className="shrink-0 text-ink-secondary" />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </main>
    </>
  );
}
