"use client";

import { WifiOff } from "lucide-react";
import { Button, ButtonLink } from "@/components/Button";
import { Wordmark } from "@/components/Wordmark";
import { NativeOffline } from "@/components/native/NativeOffline";
import { useNative } from "@/components/native/useNative";
import { useLatestBooking } from "@/lib/bookings-store";

export function OfflineScreen() {
  const booking = useLatestBooking();
  // The iOS/Android app shows its own "No connection" screen with every saved pass.
  if (useNative()) return <NativeOffline />;
  return (
    <main className="mx-auto flex min-h-dvh max-w-[480px] flex-col px-4 py-6">
      <Wordmark />
      <div className="flex flex-1 flex-col justify-center py-12">
        <WifiOff size={24} strokeWidth={1.5} aria-hidden className="text-ink-secondary" />
        <h1 className="mt-4 text-26 font-semibold text-ink">You&apos;re offline</h1>
        <p className="mt-2 text-16 text-ink-secondary">
          This page isn&apos;t saved on your phone yet. Pages you&apos;ve opened, your last search and your booking pass
          still work without a connection.
        </p>
        <div className="mt-8 grid gap-3">
          {booking && (
            <ButtonLink href={`/pass/${booking.id}`} variant="outline" fullWidth>
              Open your booking pass
            </ButtonLink>
          )}
          <Button variant="ink" fullWidth onClick={() => window.location.reload()}>
            Try again
          </Button>
        </div>
      </div>
    </main>
  );
}
