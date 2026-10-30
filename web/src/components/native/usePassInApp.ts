"use client";

import { useEffect, useRef, useState } from "react";
import type { BookingView } from "@/lib/api";
import { haptic, readSavedPasses, savePass } from "@/lib/native/plugins";
import { asBookingView } from "@/lib/native/saved-passes";
import { useNative } from "./useNative";

/**
 * The pass in the app: every version the traveller sees is saved on the phone; with no connection the saved copy
 * is shown instead; and the phone gives a success buzz when the hotel confirms while the pass is open.
 * Does nothing on the website.
 */
export function usePassInApp(bookingId: string, live: BookingView | undefined, failed: boolean) {
  const native = useNative();
  const [saved, setSaved] = useState<BookingView | null>(null);
  const lastStatus = useRef<BookingView["status"] | null>(null);

  useEffect(() => {
    if (!native || !live) return;
    void savePass(live);
    if (lastStatus.current === "DEPOSIT_PAID" && live.status === "HOTEL_CONFIRMED") void haptic("success");
    lastStatus.current = live.status;
  }, [native, live]);

  useEffect(() => {
    if (!native || live || !failed) return;
    void readSavedPasses().then((passes) => {
      const pass = passes.find((p) => p.id === bookingId);
      if (pass) setSaved(asBookingView(pass));
    });
  }, [native, live, failed, bookingId]);

  return { saved: live ? null : saved };
}
