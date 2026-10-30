"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, Star } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/Button";
import { cx, focusRing } from "@/components/ui";
import { api, type BookingView, type ShareLink } from "@/lib/api";
import { formatDay, formatInstantTime } from "@/lib/format";
import { nativeShare } from "@/lib/native/plugins";

const STAYED = new Set(["CHECKED_IN", "COMPLETED"]);
const SHAREABLE = new Set(["DEPOSIT_PAID", "HOTEL_CONFIRMED", "CHECKED_IN"]);

/** After the stay: a row to the review form, or what the traveller already said. */
export function ReviewRow({ b }: { b: BookingView }) {
  const state = useQuery({
    queryKey: ["review", b.id],
    queryFn: () => api.reviewState(b.id),
    enabled: STAYED.has(b.status),
    retry: 1,
  });
  const s = state.data;
  if (!s || (s.status !== "OPEN" && s.status !== "DONE")) return null;
  if (s.status === "DONE" && s.review) {
    return (
      <p className="mt-8 flex items-center gap-2 border-t border-hairline pt-6 text-16 text-ink-secondary">
        <span>You rated your stay</span>
        <span className="inline-flex items-center gap-1 text-ink tabular-nums">
          <Star size={16} strokeWidth={1.5} className="fill-ink" aria-hidden />
          {s.review.stars}
        </span>
        <span>· thank you</span>
      </p>
    );
  }
  return (
    <Link
      href={`/pass/${b.id}/review`}
      className={cx(
        "mt-8 flex min-h-16 items-center justify-between gap-4 border-y border-hairline py-4 text-ink",
        "hover:bg-soft",
        focusRing,
      )}
    >
      <span>
        <span className="block text-16 font-semibold">Rate your stay</span>
        <span className="block text-14 text-ink-secondary">
          How was {b.hotel.name}? It takes a few taps{s.closesAt ? `, until ${formatDay(s.closesAt)}` : ""}.
        </span>
      </span>
      <ChevronRight size={20} strokeWidth={1.5} aria-hidden className="shrink-0" />
    </Link>
  );
}

/** "Share with family": a read-only link with the hotel, address, map, arrival time and status. */
export function ShareStay({ b }: { b: BookingView }) {
  const queryClient = useQueryClient();
  const enabled = SHAREABLE.has(b.status);
  const link = useQuery({
    queryKey: ["share", b.id],
    queryFn: () => api.shareLink(b.id),
    enabled,
    retry: 1,
  });
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (!enabled) return null;

  async function send(share: ShareLink) {
    const text = `I'm staying at ${b.hotel.name} tonight. Here's where, and when I'll get there:`;
    // The app's native share sheet; the website carries on below.
    if (await nativeShare(`${b.hotel.name} · LateStay`, text, share.url)) return;
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title: `${b.hotel.name} · LateStay`, text, url: share.url });
        setNote(null);
        return;
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") return;
      }
    }
    try {
      await navigator.clipboard.writeText(share.url);
      setNote("Link copied. Paste it into a message to your family.");
    } catch {
      setNote("Copy the link above and send it to your family.");
    }
  }

  async function run(action: () => Promise<ShareLink | null>, then?: (l: ShareLink) => Promise<void>) {
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      const result = await action();
      queryClient.setQueryData(["share", b.id], result);
      if (result && then) await then(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : "That didn't go through. Try again.");
    } finally {
      setBusy(false);
    }
  }

  const current = link.data ?? null;
  return (
    <section aria-labelledby="share" className="mt-8 border-t border-hairline pt-6">
      <h2 id="share" className="text-18 font-semibold text-ink">
        Share with family
      </h2>
      <p className="mt-1 text-14 text-ink-secondary">
        A link that shows the hotel, its address and map, your arrival time and whether you&apos;ve checked in. It
        never shows your code, phone numbers or prices.
      </p>
      {current ? (
        <>
          <p className="mt-4 break-all rounded-control bg-soft px-3 py-2 text-14 text-ink">{current.url}</p>
          <p className="mt-2 text-14 text-ink-secondary">
            Works until {formatInstantTime(current.expiresAt)}, {formatDay(current.expiresAt)}, unless you turn it
            off.
          </p>
          <div className="mt-4 grid gap-3 sm:grid-cols-3">
            <Button variant="outline" onClick={() => send(current)} disabled={busy}>
              Share link
            </Button>
            <Button variant="ghost" onClick={() => run(async () => (await api.turnOffShareLink(b.id), null))} disabled={busy}>
              Turn off link
            </Button>
            <Button variant="ghost" onClick={() => run(() => api.createShareLink(b.id))} disabled={busy}>
              New link
            </Button>
          </div>
        </>
      ) : (
        <Button
          variant="outline"
          fullWidth
          className="mt-4"
          onClick={() => run(() => api.createShareLink(b.id), send)}
          disabled={busy || link.isPending}
        >
          {busy ? "Making a link…" : "Share with family"}
        </Button>
      )}
      {note && <p className="mt-3 text-14 text-ink">{note}</p>}
      {error && (
        <p role="alert" className="mt-3 text-14 text-error">
          {error}
        </p>
      )}
    </section>
  );
}
