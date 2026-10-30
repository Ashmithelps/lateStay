"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, MessageCircle } from "lucide-react";
import { Fragment, useState, type FormEvent } from "react";
import { Button } from "@/components/Button";
import { Stepper } from "@/components/GuestStepper";
import { Wordmark } from "@/components/Wordmark";
import { cx, focusRing } from "@/components/ui";
import { ApiError } from "@/lib/api";
import { formatDate, formatDay, formatPaise, formatPrice, formatTime, guestsLabel } from "@/lib/format";
import { floorLabel, ownerApi, type OwnerFloor, type OwnerSummary } from "@/lib/owner-api";
import { tagLabel } from "@/lib/reviews";

const KEY = ["owner", "summary"];

/** The hotel owner's page: tonight, earnings and payouts, lowest prices. Mobile first; no ember (nothing to buy). */
export function OwnerScreen() {
  const summary = useQuery({
    queryKey: KEY,
    queryFn: ownerApi.summary,
    retry: (count, error) => !(error instanceof ApiError && error.status === 401) && count < 2,
    refetchInterval: 30_000,
  });

  if (summary.error instanceof ApiError && summary.error.status === 401) return <OwnerSignedOut />;
  if (summary.isError) {
    return (
      <Shell>
        <p className="mt-10 text-16 text-error">We couldn&apos;t load your page. Check your connection and try again.</p>
        <Button variant="outline" className="mt-4" onClick={() => summary.refetch()}>
          Try again
        </Button>
      </Shell>
    );
  }
  if (!summary.data) {
    return (
      <Shell>
        <div className="mt-8 grid gap-4" aria-busy="true">
          <div className="h-8 w-2/3 animate-pulse rounded-control bg-soft" />
          <div className="h-24 animate-pulse rounded-card bg-soft" />
          <div className="h-40 animate-pulse rounded-card bg-soft" />
        </div>
      </Shell>
    );
  }
  const s = summary.data;
  return (
    <Shell hotelName={s.hotelName}>
      <h1 className="mt-6 text-26 font-semibold text-ink">{s.hotelName}</h1>
      <p className="text-14 text-ink-secondary">
        {s.town} · tonight is {formatDay(s.tonight)}
      </p>
      <RoomsTonight s={s} />
      <TonightBookings s={s} />
      <Earnings s={s} />
      <HowYoureDoing s={s} />
      <LowestPrices s={s} />
    </Shell>
  );
}

function Shell({ hotelName, children }: { hotelName?: string; children: React.ReactNode }) {
  const queryClient = useQueryClient();
  const signOut = useMutation({
    mutationFn: ownerApi.signOut,
    onSettled: () => queryClient.setQueryData(KEY, null),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: KEY }),
  });
  return (
    <div className="min-h-dvh bg-canvas">
      <header className="sticky top-0 z-10 border-b border-hairline bg-canvas">
        <div className="mx-auto flex min-h-16 max-w-[560px] items-center gap-2 px-4">
          <Wordmark />
          <span className="text-14 font-medium text-ink-secondary">for hotels</span>
          {hotelName && (
            <button
              type="button"
              onClick={() => signOut.mutate()}
              className={cx("ml-auto min-h-11 rounded-control px-3 text-14 font-medium text-ink hover:bg-soft", focusRing)}
            >
              Sign out
            </button>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-[560px] px-4 pb-16">{children}</main>
    </div>
  );
}

const section = "mt-8 border-t border-hairline pt-6";
const sectionTitle = "text-18 font-semibold text-ink";

function RoomsTonight({ s }: { s: OwnerSummary }) {
  const queryClient = useQueryClient();
  const [rooms, setRooms] = useState(s.roomsFree ?? 0);
  const save = useMutation({
    mutationFn: () => ownerApi.roomsTonight(rooms),
    onSuccess: (data) => queryClient.setQueryData(KEY, data),
  });
  const changed = rooms !== s.roomsFree;
  return (
    <section className={section} aria-labelledby="rooms-title">
      <h2 id="rooms-title" className="sr-only">
        Rooms free tonight
      </h2>
      <Stepper
        label="Rooms free tonight"
        hint={
          s.roomsFree === null
            ? "You haven't told us yet, so guests can't find you tonight."
            : `Guests can still book ${s.roomsAvailable} ${s.roomsAvailable === 1 ? "room" : "rooms"}.`
        }
        value={rooms}
        onChange={setRooms}
        min={0}
        max={50}
        lessLabel="One room fewer"
        moreLabel="One room more"
      />
      <div className="mt-4 flex items-center gap-3">
        <Button variant="ink" onClick={() => save.mutate()} disabled={!changed || save.isPending}>
          {save.isPending ? "Saving…" : "Save rooms"}
        </Button>
        {save.isSuccess && !changed && (
          <span className="inline-flex items-center gap-1 text-14 text-pine" role="status">
            <Check size={16} strokeWidth={1.5} aria-hidden /> Saved
          </span>
        )}
        {save.error && (
          <span role="alert" className="text-14 text-error">
            {save.error.message}
          </span>
        )}
      </div>
    </section>
  );
}

const STATUS = {
  AWAITING_CONFIRMATION: { label: "Confirm on WhatsApp", tone: "text-error" },
  CONFIRMED: { label: "Not arrived yet", tone: "text-ink-secondary" },
  CHECKED_IN: { label: "Checked in", tone: "text-pine" },
  COMPLETED: { label: "Checked in", tone: "text-pine" },
  NO_SHOW: { label: "Didn't arrive", tone: "text-ink-secondary" },
  DISPUTED: { label: "Under review", tone: "text-error" },
} as const;

function TonightBookings({ s }: { s: OwnerSummary }) {
  return (
    <section className={section} aria-labelledby="bookings-title">
      <h2 id="bookings-title" className={sectionTitle}>
        Tonight&apos;s guests
      </h2>
      {s.bookings.length === 0 ? (
        <p className="mt-2 text-14 text-ink-secondary">No LateStay guests yet tonight. We&apos;ll message you on WhatsApp when someone books.</p>
      ) : (
        <ul className="mt-3 divide-y divide-hairline">
          {s.bookings.map((b) => (
            <li key={b.id} className="flex items-start justify-between gap-4 py-3">
              <div className="min-w-0">
                <p className="truncate text-16 font-medium text-ink">{b.guestName}</p>
                <p className="text-14 text-ink-secondary tabular-nums">
                  {[guestsLabel(b.guests), ...(b.rooms > 1 ? [`${b.rooms} rooms`] : []), `arriving ${formatTime(b.arriveAt)}`, `code ${b.code}`, `collect ${formatPrice(b.payAtDesk)}`].map(
                    (part, i) => (
                      <Fragment key={part}>
                        {i > 0 && " · "}
                        <span className="whitespace-nowrap">{part}</span>
                      </Fragment>
                    ),
                  )}
                </p>
              </div>
              <span className={cx("inline-flex shrink-0 items-center gap-1 text-14 font-medium", STATUS[b.status].tone)}>
                {b.checkedIn && <Check size={16} strokeWidth={1.5} aria-hidden />}
                {STATUS[b.status].label}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Earnings({ s }: { s: OwnerSummary }) {
  const { earnings } = s;
  return (
    <section className={section} aria-labelledby="earnings-title">
      <h2 id="earnings-title" className={sectionTitle}>
        Earnings
      </h2>
      <dl className="mt-3 grid grid-cols-2 gap-4">
        <div>
          <dt className="text-14 text-ink-secondary">This week (from {formatDate(earnings.weekStart)})</dt>
          <dd className="mt-1 text-26 font-semibold text-ink tabular-nums">{formatPaise(earnings.thisWeekPaise)}</dd>
        </div>
        <div>
          <dt className="text-14 text-ink-secondary">Next payout</dt>
          <dd className="mt-1 text-18 font-semibold text-ink">{formatDay(earnings.nextPayoutDate)}</dd>
          <dd className="text-14 text-ink-secondary tabular-nums">{formatPaise(earnings.owedPaise)} owed to you</dd>
        </div>
      </dl>
      <p className="mt-3 text-14 text-ink-secondary">
        You get each guest&apos;s online deposit minus LateStay&apos;s commission, paid by UPI every Monday. The rest you
        collect at the desk.
      </p>

      <h3 className="mt-6 text-16 font-semibold text-ink">Past payouts</h3>
      {s.settlements.length === 0 ? (
        <p className="mt-1 text-14 text-ink-secondary">None yet.</p>
      ) : (
        <ul className="mt-2 divide-y divide-hairline">
          {s.settlements.map((p) => (
            <li key={p.id} className="flex items-start justify-between gap-4 py-3 text-14">
              <div className="min-w-0">
                <p className="text-ink">
                  {formatDate(p.periodStart)} – {formatDate(p.periodEnd)}
                </p>
                <p className="truncate text-ink-secondary">
                  {p.status === "PAID" ? `Paid · UPI ${p.upiReference}` : "Being paid this week"}
                </p>
              </div>
              <span className="shrink-0 text-16 font-semibold text-ink tabular-nums">{formatPaise(p.amountPaise)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The last 30 days as LateStay ranks them, and what guests said. */
function HowYoureDoing({ s }: { s: OwnerSummary }) {
  const r = s.reliability;
  const answered = r.confirmed + r.declined + r.timedOut;
  const { rating, recent } = s.reviews;
  return (
    <section className={section} aria-labelledby="doing-title">
      <h2 id="doing-title" className={sectionTitle}>
        How you&apos;re doing
      </h2>
      {r.pausedAt && (
        <p role="status" className="mt-3 rounded-control border border-error px-3 py-2 text-14 text-ink">
          Your hotel is paused from LateStay search while we look at recent bookings. Message LateStay support to talk
          it through.
        </p>
      )}
      <dl className="mt-3 grid grid-cols-2 gap-4">
        <div>
          <dt className="text-14 text-ink-secondary">Confirmed, last 30 days</dt>
          <dd className="mt-1 text-26 font-semibold text-ink tabular-nums">
            {r.confirmRatePercent === null ? "–" : `${r.confirmRatePercent}%`}
          </dd>
          <dd className="text-14 text-ink-secondary tabular-nums">
            {r.confirmed} of {answered} {answered === 1 ? "booking" : "bookings"}
          </dd>
        </div>
        <div>
          <dt className="text-14 text-ink-secondary">Usual time to confirm</dt>
          <dd className="mt-1 text-26 font-semibold text-ink tabular-nums">
            {r.medianMinutesToConfirm === null ? "–" : `${r.medianMinutesToConfirm} min`}
          </dd>
        </div>
        <div>
          <dt className="text-14 text-ink-secondary">Guests we had to move</dt>
          <dd className="mt-1 text-18 font-semibold text-ink tabular-nums">{r.rescues}</dd>
        </div>
        <div>
          <dt className="text-14 text-ink-secondary">Disputes decided for the guest</dt>
          <dd className="mt-1 text-18 font-semibold text-ink tabular-nums">{r.upheldDisputes}</dd>
        </div>
      </dl>
      <p className="mt-3 text-14 text-ink-secondary">
        Nearby hotels that confirm quickly come first in search. Confirm within 5 minutes and keep rooms you&apos;ve
        confirmed.
      </p>

      <h3 className="mt-6 text-16 font-semibold text-ink">Guest ratings</h3>
      <p className="mt-1 text-14 text-ink-secondary">
        {rating.value === null
          ? `Shown as "New" until 3 guests have rated you (${rating.count} so far).`
          : `${rating.value.toFixed(1)} from ${rating.count} ${rating.count === 1 ? "rating" : "ratings"}.`}
      </p>
      {recent.length > 0 && (
        <ul className="mt-2 divide-y divide-hairline">
          {recent.map((review, i) => (
            <li key={i} className="py-3 text-14">
              <p className="text-ink-secondary tabular-nums">
                {review.stars} of 5{review.tags.length > 0 && ` · ${review.tags.map(tagLabel).join(", ")}`}
              </p>
              {review.text && <p className="mt-1 text-ink">{review.text}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function LowestPrices({ s }: { s: OwnerSummary }) {
  return (
    <section className={section} aria-labelledby="prices-title">
      <h2 id="prices-title" className={sectionTitle}>
        Lowest prices
      </h2>
      <p className="mt-1 text-14 text-ink-secondary">
        The least LateStay will agree for a room. It can&apos;t be above the room&apos;s list price.
      </p>
      {s.rooms.map((room) => (
        <div key={room.id} className="mt-5">
          <p className="text-16 font-medium text-ink">
            {room.name} <span className="text-14 font-normal text-ink-secondary">· list price {formatPrice(room.listPrice)}</span>
          </p>
          {room.floors.length === 0 ? (
            <p className="mt-1 text-14 text-ink-secondary">No lowest price set. Ask LateStay to add one.</p>
          ) : (
            <ul className="mt-1 divide-y divide-hairline">
              {room.floors.map((f) => (
                <FloorRow key={f.id} floor={f} listPrice={room.listPrice} />
              ))}
            </ul>
          )}
        </div>
      ))}
    </section>
  );
}

function FloorRow({ floor, listPrice }: { floor: OwnerFloor; listPrice: number }) {
  const queryClient = useQueryClient();
  const [value, setValue] = useState(String(floor.floorPrice));
  const amount = Number(value);
  const problem =
    !value || !Number.isInteger(amount) || amount <= 0
      ? "Enter a price in rupees."
      : amount > listPrice
        ? `Can't be above the list price of ${formatPrice(listPrice)}.`
        : null;
  const save = useMutation({
    mutationFn: () => ownerApi.floorPrice(floor.id, amount),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: KEY }),
  });
  const id = `floor-${floor.id}`;
  const changed = amount !== floor.floorPrice;

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!problem && changed) save.mutate();
  }

  return (
    <li className="py-3">
      <form onSubmit={submit} className="grid gap-2" noValidate>
        <label htmlFor={id} className="text-14 text-ink-secondary">
          {floorLabel(floor, formatDate)}
        </label>
        <div className="flex items-center gap-3">
          <div
            className={cx(
              "flex h-12 min-w-0 flex-1 items-center rounded-control border bg-soft px-4 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-ink",
              problem && changed ? "border-error" : "border-ink-secondary",
            )}
          >
            <span className="text-16 text-ink-secondary" aria-hidden>
              ₹
            </span>
            <input
              id={id}
              inputMode="numeric"
              value={value}
              onChange={(e) => setValue(e.target.value.replace(/\D/g, ""))}
              aria-invalid={problem && changed ? true : undefined}
              aria-describedby={problem && changed ? `${id}-error` : undefined}
              className="h-full min-w-0 flex-1 bg-transparent pl-1 text-16 text-ink tabular-nums outline-none"
            />
          </div>
          <Button type="submit" variant="outline" disabled={!changed || !!problem || save.isPending}>
            {save.isPending ? "Saving…" : save.isSuccess && !changed ? "Saved" : "Save"}
          </Button>
        </div>
        {problem && changed && (
          <p id={`${id}-error`} className="text-14 text-error">
            {problem}
          </p>
        )}
        {save.error && (
          <p role="alert" className="text-14 text-error">
            {save.error.message}
          </p>
        )}
      </form>
    </li>
  );
}

/** No cookie, or an old one: how to get a link (owners have no password). */
export function OwnerSignedOut({ expired = false }: { expired?: boolean }) {
  return (
    <main className="mx-auto flex min-h-dvh max-w-[560px] flex-col px-4 py-6">
      <Wordmark />
      <h1 className="mt-10 text-26 font-semibold text-ink">
        {expired ? "This link has expired" : "Sign in from WhatsApp"}
      </h1>
      <p className="mt-2 text-16 text-ink-secondary">
        {expired
          ? "Sign-in links work once, for 15 minutes."
          : "Your hotel page has no password. LateStay sends you a sign-in link on WhatsApp."}
      </p>
      <p className="mt-6 flex items-start gap-3 text-16 text-ink">
        <MessageCircle size={24} strokeWidth={1.5} className="shrink-0" aria-hidden />
        <span>
          Send <strong className="font-semibold">hisaab</strong> to LateStay on WhatsApp, the same number that asks you
          about rooms every evening. Open the link in the reply.
        </span>
      </p>
    </main>
  );
}
