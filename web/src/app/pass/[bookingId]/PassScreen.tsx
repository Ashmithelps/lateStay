"use client";

import { useQueryClient } from "@tanstack/react-query";
import { MapPin, Phone } from "lucide-react";
import { useParams } from "next/navigation";
import { useState } from "react";
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
import { useLastSearchHref } from "@/lib/search-state";

/** The booking pass: waiting for the hotel, the desk code once confirmed, or what happens next if it fell through. */
export function PassScreen({ initial }: { initial?: BookingView }) {
  const { bookingId } = useParams<{ bookingId: string }>();
  const booking = useBooking(bookingId, initial);
  const backHref = useLastSearchHref();
  const b = booking.data;

  return (
    <>
      <PageHeader backHref={backHref} backLabel="Back to search" maxWidth="max-w-[640px]" />
      <main className="mx-auto max-w-[640px] px-4 pb-16 pt-6 sm:px-6">
        {booking.isPending ? (
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
            <OfflineNote className="mb-6">
              You&apos;re offline. This pass was last updated at {formatInstantTime(new Date(booking.dataUpdatedAt).toISOString())}.
              Your code still works at the desk.
            </OfflineNote>
            <Pass b={b} />
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
      return (
        <Closed
          b={b}
          title="Marked as a no-show"
          body={`You hadn't checked in an hour after ${formatTime(b.arriveBy)}, so ${b.hotel.name} kept the ${formatPrice(b.depositAmount)} deposit.`}
        />
      );
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
    </>
  );
}

function Confirmed({ b }: { b: BookingView }) {
  const directions = `https://www.google.com/maps/dir/?api=1&destination=${b.hotel.lat},${b.hotel.lng}`;
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
    </>
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
    </>
  );
}
