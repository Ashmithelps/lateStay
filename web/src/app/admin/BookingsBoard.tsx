"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import type { AdminApi, AdminBooking } from "@/lib/admin-api";
import type { BookingStatus } from "@/lib/api";
import { formatDay, formatInstantTime, formatPrice, formatTime } from "@/lib/format";
import { formatIndianMobile } from "@/lib/phone";
import { inputClass, PanelMessage, SmallButton, statusLabel, StatusTag } from "./admin-ui";

const COLUMNS: { title: string; statuses: BookingStatus[] }[] = [
  { title: "Disputes", statuses: ["DISPUTED"] },
  { title: "Needs confirmation", statuses: ["DEPOSIT_PAID"] },
  { title: "Awaiting payment", statuses: ["PENDING_PAYMENT"] },
  { title: "Confirmed", statuses: ["HOTEL_CONFIRMED", "CHECKED_IN"] },
  {
    title: "Closed",
    statuses: [
      "COMPLETED",
      "REFUNDED",
      "HOTEL_DECLINED",
      "HOTEL_TIMEOUT",
      "CANCELLED",
      "EXPIRED",
      "NO_SHOW",
      "HOTEL_FAILED",
    ],
  },
];

/** Bookings grouped by status, refreshed every 5 seconds. Confirm and Decline act for the hotel. */
export function BookingsBoard({ api }: { api: AdminApi }) {
  const bookings = useQuery({ queryKey: ["admin", "bookings"], queryFn: api.bookings, refetchInterval: 5000 });
  const now = useNow();

  if (bookings.isPending) return <PanelMessage>Loading bookings…</PanelMessage>;
  if (!bookings.data) return <PanelMessage>{bookings.error?.message ?? "Couldn't load bookings."}</PanelMessage>;

  return (
    <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-5">
      {COLUMNS.map((column) => {
        const rows = bookings.data.filter((b) => column.statuses.includes(b.status));
        return (
          <section key={column.title} aria-labelledby={`col-${column.title}`} className="min-w-0 overflow-hidden">
            <h2
              id={`col-${column.title}`}
              className="flex items-baseline justify-between border-b border-ink pb-2 text-14 font-semibold text-ink"
            >
              {column.title}
              <span className="text-12 font-medium text-ink-secondary tabular-nums">{rows.length}</span>
            </h2>
            {rows.length === 0 ? (
              <p className="py-4 text-14 text-ink-secondary">None</p>
            ) : (
              <ul className="divide-y divide-hairline">
                {rows.map((b) => (
                  <li key={b.id}>
                    <BookingRow b={b} api={api} now={now} showStatus={column.statuses.length > 1} />
                  </li>
                ))}
              </ul>
            )}
          </section>
        );
      })}
    </div>
  );
}

function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return now;
}

function countdown(until: string, now: number): string {
  const seconds = Math.max(0, Math.round((new Date(until).getTime() - now) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/** Rescue: the hotel let the traveller down (refund now, counts against the hotel). */
const RESCUABLE: BookingStatus[] = [
  "DEPOSIT_PAID",
  "HOTEL_CONFIRMED",
  "CHECKED_IN",
  "NO_SHOW",
  "DISPUTED",
  "COMPLETED",
];

function BookingRow({ b, api, now, showStatus }: { b: AdminBooking; api: AdminApi; now: number; showStatus: boolean }) {
  const queryClient = useQueryClient();
  const [code, setCode] = useState("");
  const [rescuing, setRescuing] = useState(false);
  const act = useMutation({
    mutationFn: (action: "confirm" | "decline" | "check-in" | "stayed" | "not-arrived" | "rescue" | "keep-hidden") => {
      switch (action) {
        case "confirm":
          return api.confirm(b.id);
        case "decline":
          return api.decline(b.id);
        case "stayed":
          return api.settle(b.id, "GUEST_STAYED");
        case "not-arrived":
          return api.settle(b.id, "GUEST_DID_NOT_ARRIVE");
        case "rescue":
          return api.rescue(b.id);
        case "keep-hidden":
          return api.keepHidden(b.id);
        default:
          return api.checkIn(b.id, code);
      }
    },
    onSettled: () => {
      setRescuing(false);
      void queryClient.invalidateQueries({ queryKey: ["admin", "bookings"] });
    },
  });
  const canRescue = RESCUABLE.includes(b.status);

  const tone =
    b.status === "HOTEL_CONFIRMED" || b.status === "CHECKED_IN" || b.status === "COMPLETED"
      ? "pine"
      : ["HOTEL_DECLINED", "HOTEL_TIMEOUT", "NO_SHOW", "DISPUTED", "HOTEL_FAILED"].includes(b.status)
        ? "error"
        : "neutral";

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-2 py-3 text-14">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-semibold text-ink">{b.guestName}</p>
          <p className="truncate text-ink-secondary">
            {b.hotelName} · {b.roomType}
          </p>
        </div>
        {showStatus && <StatusTag tone={tone}>{statusLabel(b.status)}</StatusTag>}
      </div>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-ink-secondary tabular-nums">
        <dt>Arrive</dt>
        <dd className="text-ink">
          {formatTime(b.arriveAt)} {formatDay(b.arriveAt)} (by {formatTime(b.arriveBy)})
        </dd>
        <dt>Guests</dt>
        <dd className="text-ink">
          {b.guests} · {b.phone ? formatIndianMobile(b.phone) : "phone deleted"}
        </dd>
        <dt>Price</dt>
        <dd className="text-ink">
          {formatPrice(b.agreedPrice)} ({formatPrice(b.depositAmount)} paid online, {formatPrice(b.payAtDesk)} at desk)
        </dd>
        {b.paymentStatus && b.paymentStatus !== "CREATED" && (
          <>
            <dt>Payment</dt>
            <dd className="text-ink">{b.paymentStatus.replace(/_/g, " ").toLowerCase()}</dd>
          </>
        )}
      </dl>

      {b.status === "DISPUTED" && (
        <div className="grid gap-2 rounded-control bg-soft p-3">
          <p className="font-medium text-ink">
            {b.disputeReason === "TURNED_AWAY"
              ? "Guest says the hotel turned them away."
              : "Hotel said no-show; the guest says they arrived."}
          </p>
          {b.disputeReason === "TURNED_AWAY" && (
            <p className="text-12 text-ink-secondary">
              {b.keepHiddenAt
                ? "Hidden from search tonight until you settle this."
                : b.hideUntil && Date.parse(b.hideUntil) > now
                  ? `Hidden from search until ${formatInstantTime(b.hideUntil)}, then shown again.`
                  : "Shown in search again (the hide period is over)."}
            </p>
          )}
          {b.hotelFailures > 0 && (
            <p className="text-12 text-ink-secondary">
              {b.hotelName} has failed {b.hotelFailures} {b.hotelFailures === 1 ? "booking" : "bookings"} before.
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <SmallButton variant="ink" onClick={() => act.mutate("stayed")} disabled={act.isPending}>
              Guest stayed
            </SmallButton>
            <SmallButton onClick={() => act.mutate("not-arrived")} disabled={act.isPending}>
              Guest didn&apos;t arrive
            </SmallButton>
            {b.disputeReason === "TURNED_AWAY" && !b.keepHiddenAt && (
              <SmallButton variant="ghost" onClick={() => act.mutate("keep-hidden")} disabled={act.isPending}>
                Keep hidden
              </SmallButton>
            )}
          </div>
        </div>
      )}
      {b.status === "DEPOSIT_PAID" && (
        <div className="flex flex-wrap items-center gap-2">
          <SmallButton variant="ink" onClick={() => act.mutate("confirm")} disabled={act.isPending}>
            Confirm
          </SmallButton>
          <SmallButton onClick={() => act.mutate("decline")} disabled={act.isPending}>
            Decline
          </SmallButton>
          {b.confirmBy && (
            <span className="ml-auto text-12 font-medium text-ink-secondary tabular-nums">
              {countdown(b.confirmBy, now)} left
            </span>
          )}
        </div>
      )}
      {b.status === "PENDING_PAYMENT" && (
        <p className="text-12 text-ink-secondary">Held until {formatInstantTime(b.payBy)}</p>
      )}
      {b.status === "HOTEL_CONFIRMED" && (
        <form
          className="flex items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            act.mutate("check-in");
          }}
        >
          <label className="sr-only" htmlFor={`code-${b.id}`}>
            Code from the guest
          </label>
          <input
            id={`code-${b.id}`}
            inputMode="numeric"
            maxLength={4}
            placeholder="Guest's code"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            className={inputClass}
          />
          <SmallButton type="submit" disabled={code.length !== 4 || act.isPending}>
            Check in
          </SmallButton>
        </form>
      )}
      {canRescue &&
        (rescuing ? (
          <div className="grid gap-2">
            <p className="text-12 text-ink-secondary">
              Refunds {formatPrice(b.depositAmount)} now, marks the booking hotel failed and counts it against{" "}
              {b.hotelName}.
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <SmallButton variant="ink" onClick={() => act.mutate("rescue")} disabled={act.isPending}>
                Confirm rescue
              </SmallButton>
              <SmallButton variant="ghost" onClick={() => setRescuing(false)} disabled={act.isPending}>
                Keep
              </SmallButton>
            </div>
          </div>
        ) : (
          <SmallButton variant="ghost" className="justify-self-start" onClick={() => setRescuing(true)}>
            Rescue
          </SmallButton>
        ))}
      {act.error && (
        <p role="alert" className="text-12 text-error">
          {act.error.message}
        </p>
      )}
    </div>
  );
}
