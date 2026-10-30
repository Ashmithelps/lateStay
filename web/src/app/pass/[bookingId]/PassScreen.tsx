"use client";

import { useQueryClient } from "@tanstack/react-query";
import { ChevronRight, MapPin, MessageCircle, Phone } from "lucide-react";
import { useParams } from "next/navigation";
import { createContext, useContext, useState } from "react";
import { Button, ButtonLink } from "@/components/Button";
import { DetailRows, type DetailRow } from "@/components/DetailRows";
import { OfflineNote } from "@/components/OfflineNote";
import { PageHeader } from "@/components/PageHeader";
import { Photo } from "@/components/Photo";
import { TypingDots } from "@/components/TypingDots";
import { cx, focusRing } from "@/components/ui";
import { api, type BookingView } from "@/lib/api";
import { formatDay, formatDistance, formatInstantTime, formatPrice, formatTime, guestsLabel } from "@/lib/format";
import { formatIndianMobile } from "@/lib/phone";
import { useBooking } from "@/lib/queries";
import { ReviewRow, ShareStay } from "./PassExtras";
import { NotifyPrompt } from "@/components/native/NotifyPrompt";
import { useNative } from "@/components/native/useNative";
import { usePassInApp } from "@/components/native/usePassInApp";
import { mapsUrl } from "@/lib/native/links";
import { nativePlatform } from "@/lib/native/platform";
import { useLastSearchHref } from "@/lib/search-state";

/** The booking pass: waiting for the hotel, the desk code once confirmed, or what happens next if it fell through. */
/** LateStay's support WhatsApp (SUPPORT_WHATSAPP_NUMBER, read on the server by the page). */
const SupportNumber = createContext<string | null>(null);

export function PassScreen({ initial, supportWhatsApp }: { initial?: BookingView; supportWhatsApp?: string | null }) {
  const { bookingId } = useParams<{ bookingId: string }>();
  const booking = useBooking(bookingId, initial);
  const backHref = useLastSearchHref();
  // In the app, a copy saved on the phone stands in when there's no connection.
  const { saved } = usePassInApp(bookingId, booking.data, booking.isError);
  const b = booking.data ?? saved ?? undefined;

  return (
    <>
      <PageHeader backHref={backHref} backLabel="Back to search" maxWidth="max-w-[640px]" />
      <main className="mx-auto max-w-[640px] px-4 pb-16 pt-6 sm:px-6">
        {booking.isPending && !b ? (
          <div className="h-64 animate-pulse rounded-card bg-soft" aria-busy="true" />
        ) : !b ? (
          <div className="py-12 text-center">
            <h1 className="text-22 font-semibold text-ink">We couldn&apos;t find this booking</h1>
            <p className="mt-2 text-16 text-ink-secondary">Check the link, or search again.</p>
            <ButtonLink href={backHref} variant="outline" className="mt-6">
              Back to search
            </ButtonLink>
          </div>
        ) : (
          <div aria-live="polite">
            {saved && (
              <p role="status" className="mb-6 text-14 text-ink-secondary">
                No connection. This is the copy saved on this phone; your code works at the desk.
              </p>
            )}
            <OfflineNote className="mb-6">
              You&apos;re offline. This pass was last updated at {formatInstantTime(new Date(booking.dataUpdatedAt).toISOString())}.
              Your code still works at the desk.
            </OfflineNote>
            <SupportNumber.Provider value={supportWhatsApp ?? null}>
              <Pass b={b} />
            </SupportNumber.Provider>
          </div>
        )}
      </main>
    </>
  );
}

function stayRows(b: BookingView): DetailRow[] {
  return [
    { term: "Arrive by", value: `${formatTime(b.arriveBy)}, ${formatDay(b.arriveBy)}` },
    { term: "Leave by", value: `${formatTime(b.leaveAt)}, ${formatDay(b.leaveAt)}` },
    { term: "Guests", value: guestsLabel(b.guests) },
    { term: "Room", value: b.roomType },
    { term: "Name", value: b.guestName },
  ];
}

function moneyRows(b: BookingView): DetailRow[] {
  return [
    { term: "Paid now", value: formatPrice(b.depositAmount) },
    { term: "Pay at desk", value: formatPrice(b.payAtDesk), strong: true },
  ];
}

function HotelLine({ b }: { b: BookingView }) {
  return (
    <div className="flex items-center gap-4">
      <Photo src={b.hotel.photo} alt="" sizes="64px" className="size-16 shrink-0 rounded-card" />
      <div className="min-w-0">
        <p className="truncate text-16 font-semibold text-ink">{b.hotel.name}</p>
        <p className="truncate text-14 text-ink-secondary">{b.hotel.address.includes(b.hotel.town) ? b.hotel.address : `${b.hotel.address}, ${b.hotel.town}`}
        </p>
      </div>
    </div>
  );
}

function Pass({ b }: { b: BookingView }) {
  switch (b.status) {
    case "PENDING_PAYMENT":
      return (
        <>
          <h1 className="text-26 font-semibold text-ink">Finish paying to hold your room</h1>
          <p className="mt-2 text-16 text-ink-secondary">
            {b.hotel.name} holds the room until {formatInstantTime(b.payBy)}.
          </p>
          <ButtonLink href={`/book/${b.sessionId}`} fullWidth className="mt-6">
            Pay {formatPrice(b.depositAmount)}
          </ButtonLink>
        </>
      );
    case "DEPOSIT_PAID":
      return <Waiting b={b} />;
    case "HOTEL_CONFIRMED":
    case "CHECKED_IN":
      return <Confirmed b={b} />;
    case "COMPLETED":
      return <Closed b={b} title="Stay complete" body={`Thanks for staying at ${b.hotel.name}.`} />;
    case "NO_SHOW":
      return <NoShow b={b} />;
    case "DISPUTED":
      return <Disputed b={b} />;
    case "HOTEL_FAILED":
      return <HotelFailed b={b} />;
    case "EXPIRED":
      return (
        <FellThrough
          b={b}
          title="The payment window closed"
          body="We held the room for 10 minutes, but no payment came through, so nothing was charged."
          retry
        />
      );
    default:
      return <FellThrough b={b} title={fellThroughTitle(b)} body={refundText(b)} />;
  }
}

function fellThroughTitle(b: BookingView): string {
  const reason = b.status === "REFUNDED" ? b.refundedFrom : b.status;
  if (reason === "HOTEL_DECLINED") return `${b.hotel.name} couldn't take this booking`;
  if (reason === "HOTEL_TIMEOUT") return `${b.hotel.name} didn't confirm in time`;
  return "Booking cancelled";
}

function refundText(b: BookingView): string {
  if (!b.refund) return "Nothing was charged.";
  const amount = formatPrice(b.refund.amount);
  return b.refund.status === "PROCESSED"
    ? `Your ${amount} has been refunded. It can take 5 to 7 working days to show in your account.`
    : `Your ${amount} refund is on its way. Razorpay usually returns it within 5 to 7 working days.`;
}

function Waiting({ b }: { b: BookingView }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function cancel() {
    setCancelling(true);
    setError(null);
    try {
      queryClient.setQueryData(["booking", b.id], await api.cancelBooking(b.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't cancel. Try again.");
      setCancelling(false);
      void queryClient.invalidateQueries({ queryKey: ["booking", b.id] });
    }
  }

  return (
    <>
      <div className="flex items-center gap-2 text-14 font-medium text-ink-secondary">
        <TypingDots />
        <span>Payment received</span>
      </div>
      <h1 className="mt-2 text-26 font-semibold text-ink">Waiting for {b.hotel.name} to confirm</h1>
      <p className="mt-2 text-16 text-ink-secondary">
        Usually under 5 minutes. If they haven&apos;t confirmed by {b.confirmBy ? formatInstantTime(b.confirmBy) : "then"},
        your {formatPrice(b.depositAmount)} is refunded automatically.
      </p>
      {/* In the app, right after paying: offer a notification when the hotel confirms. */}
      <NotifyPrompt bookingId={b.id} />
      <div className="mt-8 border-t border-hairline pt-6">
        <HotelLine b={b} />
      </div>
      <DetailRows className="mt-4 border-t border-hairline" rows={[...stayRows(b), ...moneyRows(b)]} />
      <div className="mt-8">
        {confirming ? (
          <div className="grid gap-3 sm:grid-cols-2">
            <Button variant="outline" onClick={cancel} disabled={cancelling}>
              {cancelling ? "Cancelling…" : `Cancel and refund ${formatPrice(b.depositAmount)}`}
            </Button>
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={cancelling}>
              Keep booking
            </Button>
          </div>
        ) : (
          <Button variant="outline" fullWidth onClick={() => setConfirming(true)}>
            Cancel booking
          </Button>
        )}
        {error && (
          <p role="alert" className="mt-3 text-14 text-error">
            {error}
          </p>
        )}
      </div>
      <ShareStay b={b} />
      <SupportLine b={b} />
    </>
  );
}

function Confirmed({ b }: { b: BookingView }) {
  const native = useNative();
  const platform = native ? nativePlatform() : "web";
  // The app opens the system maps app; the website opens Google Maps.
  const directions =
    platform === "web"
      ? `https://www.google.com/maps/dir/?api=1&destination=${b.hotel.lat},${b.hotel.lng}`
      : mapsUrl(platform, b.hotel.lat, b.hotel.lng, b.hotel.name);
  const tel = `tel:${b.hotel.phone.replace(/[^\d+]/g, "")}`;
  const linkClass = cx(
    "inline-flex min-h-12 items-center justify-center gap-2 rounded-control border border-ink bg-canvas px-5",
    "text-16 font-semibold text-ink transition-colors hover:bg-soft",
    focusRing,
  );
  return (
    <>
      <p className="text-14 font-medium text-pine">{b.status === "CHECKED_IN" ? "Checked in" : "Booking confirmed"}</p>
      <h1 className="mt-1 text-26 font-semibold text-ink">{b.hotel.name}</h1>
      <div className="mt-8 border-y border-hairline py-8 text-center">
        <p className="text-14 text-ink-secondary">Show this code at the desk</p>
        <p className="mt-3 text-code font-semibold pl-[0.2em] tracking-[0.2em] text-ink tabular-nums" aria-label={`Code ${b.code?.split("").join(" ")}`}>
          {b.code}
        </p>
      </div>
      <DetailRows
        rows={[
          ...stayRows(b),
          ...moneyRows(b),
          { term: "Hotel phone", value: formatIndianMobile(b.hotel.phone.replace(/\s/g, "")) },
        ]}
      />
      <div className="mt-6 grid grid-cols-2 gap-3">
        <a href={directions} target="_blank" rel="noreferrer" className={linkClass}>
          <MapPin size={20} strokeWidth={1.5} aria-hidden />
          Directions
        </a>
        <a href={tel} className={linkClass}>
          <Phone size={20} strokeWidth={1.5} aria-hidden />
          Call
        </a>
      </div>
      <p className="mt-6 text-14 text-ink-secondary">
        Every guest needs a photo ID at check-in. The hotel holds your room until {formatTime(b.arriveBy)}.
      </p>
      {b.status === "HOTEL_CONFIRMED" && (
        <p className="mt-3 text-14 text-ink-secondary">
          The hotel has confirmed, so your {formatPrice(b.depositAmount)} deposit is non-refundable. You pay{" "}
          {formatPrice(b.payAtDesk)} at the desk.
        </p>
      )}
      <ReviewRow b={b} />
      <ShareStay b={b} />
      <Help b={b} />
    </>
  );
}

/** "Problem at the hotel?": support on WhatsApp, and from the arrival time "The hotel turned me away". */
function Help({ b }: { b: BookingView }) {
  const support = useContext(SupportNumber);
  return (
    <section aria-labelledby="help" className="mt-8 border-t border-hairline pt-6">
      <h2 id="help" className="text-18 font-semibold text-ink">
        Problem at the hotel?
      </h2>
      {support && (
        <a
          href={supportLink(support, b)}
          target="_blank"
          rel="noreferrer"
          className={cx(
            "mt-3 flex min-h-12 items-center gap-3 rounded-control px-1 text-16 font-medium text-ink underline-offset-4 hover:underline",
            focusRing,
          )}
        >
          <MessageCircle size={20} strokeWidth={1.5} aria-hidden />
          Message LateStay support on WhatsApp
        </a>
      )}
      {b.actions.turnedAway && <ConfirmAction b={b} kind="turnedAway" />}
    </section>
  );
}

function supportLink(number: string, b: BookingView): string {
  const text = `Hi, I need help with my LateStay booking at ${b.hotel.name}${b.code ? ` (code ${b.code})` : ""}.`;
  return `https://wa.me/${number.replace(/\D/g, "")}?text=${encodeURIComponent(text)}`;
}

const ACTIONS = {
  turnedAway: {
    open: "The hotel turned me away",
    confirm: "Yes, they turned me away",
    question: "Tell us the hotel won't give you the room. We'll step in straight away and you won't lose your money.",
    run: (id: string) => api.turnedAway(id),
  },
  didArrive: {
    open: "I did arrive",
    confirm: "Yes, I arrived",
    question: "Tell us you reached the hotel. Someone from LateStay will check with the hotel and get back to you.",
    run: (id: string) => api.travellerArrived(id),
  },
} as const;

/** An outline button with an inline confirm step; the pass refreshes with the new status. */
function ConfirmAction({ b, kind }: { b: BookingView; kind: keyof typeof ACTIONS }) {
  const queryClient = useQueryClient();
  const action = ACTIONS[kind];
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      queryClient.setQueryData(["booking", b.id], await action.run(b.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : "That didn't go through. Try again.");
      setBusy(false);
    }
  }

  return (
    <div className="mt-3">
      {confirming ? (
        <div className="grid gap-3">
          <p className="text-14 text-ink">{action.question}</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <Button variant="outline" onClick={run} disabled={busy}>
              {busy ? "Sending…" : action.confirm}
            </Button>
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={busy}>
              Not now
            </Button>
          </div>
        </div>
      ) : (
        <Button variant="outline" fullWidth onClick={() => setConfirming(true)}>
          {action.open}
        </Button>
      )}
      {error && (
        <p role="alert" className="mt-3 text-14 text-error">
          {error}
        </p>
      )}
    </div>
  );
}

function NoShow({ b }: { b: BookingView }) {
  return (
    <>
      <h1 className="text-26 font-semibold text-ink">{b.hotel.name} says you didn&apos;t arrive</h1>
      <p className="mt-2 text-16 text-ink-secondary">
        {b.actions.didArrive
          ? `If that's wrong, tell us. Until then the hotel keeps the ${formatPrice(b.depositAmount)} deposit.`
          : `We checked with the hotel. It keeps the ${formatPrice(b.depositAmount)} deposit.`}
      </p>
      {b.actions.didArrive && <ConfirmAction b={b} kind="didArrive" />}
      <div className="mt-8 border-t border-hairline pt-6">
        <HotelLine b={b} />
      </div>
      <DetailRows className="mt-4 border-t border-hairline" rows={[...stayRows(b), ...moneyRows(b)]} />
      <SupportLine b={b} />
    </>
  );
}

function Disputed({ b }: { b: BookingView }) {
  const turnedAway = b.disputeReason === "TURNED_AWAY";
  return (
    <>
      <p className="text-14 font-medium text-ink-secondary">We&apos;re on it</p>
      <h1 className="mt-1 text-26 font-semibold text-ink">
        {turnedAway ? `We're sorting out ${b.hotel.name} for you` : "We're checking with the hotel"}
      </h1>
      <p className="mt-2 text-16 text-ink-secondary">
        {turnedAway
          ? `Someone from LateStay has been alerted and will help you now. If the hotel can't give you the room, your ${formatPrice(b.depositAmount)} comes back and we'll show you places with rooms.`
          : "You told us you arrived. Someone from LateStay will check with the hotel and settle it, usually within a day."}
      </p>
      <div className="mt-8 border-t border-hairline pt-6">
        <HotelLine b={b} />
      </div>
      <DetailRows className="mt-4 border-t border-hairline" rows={[...stayRows(b), ...moneyRows(b)]} />
      <SupportLine b={b} />
    </>
  );
}

function HotelFailed({ b }: { b: BookingView }) {
  const backHref = useLastSearchHref();
  return (
    <>
      <h1 className="text-26 font-semibold text-ink">Sorry, {b.hotel.name} let you down</h1>
      <p className="mt-2 text-16 text-ink-secondary">
        {b.refund?.status === "PROCESSED"
          ? `Your ${formatPrice(b.depositAmount)} has been refunded. It can take 5 to 7 working days to show in your account.`
          : `Your ${formatPrice(b.depositAmount)} is being refunded now.`}
      </p>
      {b.alternatives.length > 0 ? (
        <section aria-labelledby="alternatives" className="mt-8 border-t border-hairline pt-6">
          <h2 id="alternatives" className="text-18 font-semibold text-ink">
            Rooms free nearby right now
          </h2>
          <ul className="mt-4 divide-y divide-hairline">
            {b.alternatives.map((h) => (
              <li key={h.slug}>
                <a
                  href={`/hotels/${h.slug}?stay=${h.stayRequestId}`}
                  className={cx("flex items-center gap-4 rounded-control py-3", focusRing)}
                >
                  <Photo src={h.photo} alt="" sizes="80px" className="size-20 shrink-0 rounded-card" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-16 font-semibold text-ink">{h.name}</span>
                    <span className="block text-14 text-ink-secondary">{formatDistance(h.distanceKm)}</span>
                    <span className="block text-16 font-semibold text-ink tabular-nums">
                      from {formatPrice(h.fromPrice)}
                    </span>
                  </span>
                  <ChevronRight size={20} strokeWidth={1.5} aria-hidden className="shrink-0 text-ink-secondary" />
                </a>
              </li>
            ))}
          </ul>
        </section>
      ) : (
        <ButtonLink href={backHref} variant="ink" fullWidth className="mt-8">
          Search again
        </ButtonLink>
      )}
      <SupportLine b={b} />
    </>
  );
}

/** A quiet support link under the closed states. */
function SupportLine({ b }: { b: BookingView }) {
  const support = useContext(SupportNumber);
  if (!support) return null;
  return (
    <a
      href={supportLink(support, b)}
      target="_blank"
      rel="noreferrer"
      className={cx(
        "mt-6 inline-flex min-h-11 items-center gap-2 rounded-control text-14 font-medium text-ink underline-offset-4 hover:underline",
        focusRing,
      )}
    >
      <MessageCircle size={20} strokeWidth={1.5} aria-hidden />
      Message LateStay support on WhatsApp
    </a>
  );
}

function FellThrough({ b, title, body, retry }: { b: BookingView; title: string; body: string; retry?: boolean }) {
  const backHref = useLastSearchHref();
  const next = b.nextBest;
  return (
    <>
      <h1 className="text-26 font-semibold text-ink">{title}</h1>
      <p className="mt-2 text-16 text-ink-secondary">{body}</p>
      {next ? (
        <section aria-labelledby="next" className="mt-8 border-t border-hairline pt-6">
          <h2 id="next" className="text-18 font-semibold text-ink">Try the next closest</h2>
          <div className="mt-4 flex items-center gap-4">
            <Photo src={next.photo} alt="" sizes="96px" className="size-24 shrink-0 rounded-card" />
            <div className="min-w-0">
              <p className="truncate text-16 font-semibold text-ink">{next.name}</p>
              <p className="text-14 text-ink-secondary">{formatDistance(next.distanceKm)}</p>
              <p className="text-16 font-semibold text-ink tabular-nums">from {formatPrice(next.fromPrice)} tonight</p>
            </div>
          </div>
          <ButtonLink href={`/hotels/${next.slug}?stay=${next.stayRequestId}`} variant="ink" fullWidth className="mt-6">
            See {next.name}
          </ButtonLink>
        </section>
      ) : (
        <ButtonLink href={backHref} variant="ink" fullWidth className="mt-8">
          Search again
        </ButtonLink>
      )}
      {retry && (
        <ButtonLink href={`/book/${b.sessionId}`} variant="outline" fullWidth className="mt-3">
          Try {b.hotel.name} again
        </ButtonLink>
      )}
    </>
  );
}

function Closed({ b, title, body }: { b: BookingView; title: string; body: string }) {
  return (
    <>
      <h1 className="text-26 font-semibold text-ink">{title}</h1>
      <p className="mt-2 text-16 text-ink-secondary">{body}</p>
      <div className="mt-8 border-t border-hairline pt-6">
        <HotelLine b={b} />
      </div>
      <DetailRows className="mt-4 border-t border-hairline" rows={[...stayRows(b), ...moneyRows(b)]} />
      <ReviewRow b={b} />
    </>
  );
}
