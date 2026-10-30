"use client";

import { WifiOff } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/Button";
import { DetailRows } from "@/components/DetailRows";
import { Wordmark } from "@/components/Wordmark";
import { formatDay, formatPrice, formatTime } from "@/lib/format";
import { passStatusLabel } from "@/lib/native/pass-status";
import { readSavedPasses } from "@/lib/native/plugins";
import type { SavedPass } from "@/lib/native/saved-passes";

/**
 * The app's "No connection" screen: every pass saved on the phone, open, with its desk code (nothing here needs the
 * network), and Retry. Never a browser error page.
 */
export function NativeOffline() {
  const [passes, setPasses] = useState<SavedPass[]>([]);
  useEffect(() => {
    void readSavedPasses().then(setPasses);
  }, []);

  return (
    <main className="mx-auto flex min-h-dvh max-w-[480px] flex-col px-4 py-6">
      <Wordmark />
      <div className="py-10">
        <WifiOff size={24} strokeWidth={1.5} aria-hidden className="text-ink-secondary" />
        <h1 className="mt-4 text-26 font-semibold text-ink">No connection</h1>
        <p className="mt-2 text-16 text-ink-secondary">
          {passes.length > 0
            ? "Your saved bookings are below. The code works at the desk without a connection."
            : "Check your signal or Wi-Fi, then try again."}
        </p>
        <Button variant="ink" fullWidth className="mt-6" onClick={() => window.location.reload()}>
          Retry
        </Button>
      </div>
      {passes.length > 0 && (
        <section aria-labelledby="saved-title" className="border-t border-hairline pt-6">
          <h2 id="saved-title" className="text-18 font-semibold text-ink">
            Saved on this phone
          </h2>
          <ul className="mt-2 divide-y divide-hairline">
            {passes.map((p) => (
              <li key={p.id} className="py-5">
                <p className="text-14 font-medium text-ink-secondary">{passStatusLabel(p.status)}</p>
                <p className="text-18 font-semibold text-ink">{p.hotel.name}</p>
                <p className="text-14 text-ink-secondary">{p.hotel.address}</p>
                {p.code && (
                  <p className="mt-4 text-center">
                    <span className="block text-14 text-ink-secondary">Show this code at the desk</span>
                    <span
                      className="mt-2 block pl-[0.2em] text-code font-semibold tracking-[0.2em] text-ink tabular-nums"
                      aria-label={`Code ${p.code.split("").join(" ")}`}
                    >
                      {p.code}
                    </span>
                  </p>
                )}
                <DetailRows
                  className="mt-4 border-t border-hairline"
                  rows={[
                    { term: "Arrive by", value: `${formatTime(p.arriveBy)}, ${formatDay(p.arriveBy)}` },
                    { term: "Pay at desk", value: formatPrice(p.payAtDesk), strong: true },
                    { term: "Hotel phone", value: p.hotel.phone },
                  ]}
                />
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  );
}
