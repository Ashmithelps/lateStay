"use client";

import { Bell } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/Button";
import { alreadyAsked, enableFor, markAsked, pushState, registerFor } from "@/lib/native/push";
import { useNative } from "./useNative";

/**
 * After the deposit is paid (the "Waiting for the hotel" pass), in the app only: our own short card first, then the
 * system's permission prompt if the traveller taps "Turn on". Asked once per install, never at launch. With
 * permission already given, the device is registered for this booking without asking.
 */
export function NotifyPrompt({ bookingId }: { bookingId: string }) {
  const native = useNative();
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<null | "on" | "off">(null);

  useEffect(() => {
    if (!native) return;
    let cancelled = false;
    void (async () => {
      const state = await pushState();
      if (state === "granted") {
        await registerFor(bookingId).catch(() => {});
        return;
      }
      if (state === "prompt" && !(await alreadyAsked()) && !cancelled) setShow(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [native, bookingId]);

  if (!show) return null;
  if (done) {
    return (
      <p role="status" className="mt-6 text-14 text-ink-secondary">
        {done === "on"
          ? "Notifications are on. We'll tell you when the hotel confirms."
          : "No notifications. This pass updates by itself while it's open."}
      </p>
    );
  }
  return (
    <section aria-labelledby="notify-title" className="mt-6 rounded-card border border-hairline p-4">
      <div className="flex items-start gap-3">
        <Bell size={24} strokeWidth={1.5} aria-hidden className="mt-0.5 shrink-0 text-ink" />
        <div className="min-w-0">
          <h2 id="notify-title" className="text-16 font-semibold text-ink">
            Get a notification when the hotel confirms.
          </h2>
          <p className="mt-1 text-14 text-ink-secondary">
            Only about this booking: confirmed, or a refund if it falls through. No marketing.
          </p>
        </div>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-3">
        <Button
          variant="ink"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            const state = await enableFor(bookingId).catch(() => "denied" as const);
            setDone(state === "granted" ? "on" : "off");
          }}
        >
          {busy ? "Turning on…" : "Turn on"}
        </Button>
        <Button
          variant="ghost"
          disabled={busy}
          onClick={() => {
            void markAsked();
            setShow(false);
          }}
        >
          Not now
        </Button>
      </div>
    </section>
  );
}
