"use client";

import { useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useState } from "react";
import { cx, focusRing } from "@/components/ui";
import type { AdminApi, AdminDigest } from "@/lib/admin-api";
import { formatDay, formatPaise } from "@/lib/format";
import { PanelMessage } from "./admin-ui";

function shift(isoDate: string, days: number): string {
  const d = new Date(`${isoDate}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** The morning digest's numbers (also sent on WhatsApp at 09:00), for last night or an earlier one. */
export function TodayPanel({ api }: { api: AdminApi }) {
  const [night, setNight] = useState<string | undefined>(undefined);
  const digest = useQuery({ queryKey: ["admin", "today", night ?? "last"], queryFn: () => api.today(night) });
  const d = digest.data;
  const shown = night ?? d?.night;

  return (
    <section aria-labelledby="today-title" className="min-w-0 max-w-[960px]">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="today-title" className="text-18 font-semibold text-ink">
          {d ? `Night of ${formatDay(d.night)}` : "Last night"}
        </h2>
        <div className="flex items-center gap-1" role="group" aria-label="Choose the night">
          <StepButton label="Night before" disabled={!shown} onClick={() => shown && setNight(shift(shown, -1))}>
            <ChevronLeft size={16} strokeWidth={1.5} />
          </StepButton>
          <input
            type="date"
            aria-label="Night of"
            value={shown ?? ""}
            onChange={(e) => setNight(e.target.value || undefined)}
            className={cx("h-10 rounded-control border border-ink-secondary bg-soft px-3 text-14 text-ink tabular-nums", focusRing)}
          />
          <StepButton label="Night after" disabled={!shown} onClick={() => shown && setNight(shift(shown, 1))}>
            <ChevronRight size={16} strokeWidth={1.5} />
          </StepButton>
        </div>
      </div>
      <p className="mt-1 text-12 text-ink-secondary">
        Bookings for that night; refunds and LLM cost from 9:00 am that day to 9:00 am the next. Sent to the admin on
        WhatsApp every morning at 9:00.
      </p>
      {digest.isPending ? (
        <PanelMessage>Loading…</PanelMessage>
      ) : digest.error ? (
        <PanelMessage>{digest.error.message}</PanelMessage>
      ) : d ? (
        <DigestView d={d} />
      ) : null}
    </section>
  );
}

function DigestView({ d }: { d: AdminDigest }) {
  return (
    <div className="mt-5 grid gap-8">
      <Group
        title="Bookings"
        items={[
          ["Booked", String(d.bookings)],
          ["Checked in", String(d.checkedIn)],
          ["Completed", String(d.completed)],
          ["No-shows", String(d.noShows)],
          ["Rescues", String(d.rescues)],
        ]}
      />
      <Group
        title="Money"
        items={[
          ["Your commission", formatPaise(d.commissionPaise)],
          ["Owed to hotels", formatPaise(d.owedToHotelsPaise)],
          ["Refunds", `${d.refunds} · ${formatPaise(d.refundsPaise)}`],
          ["LLM cost", formatPaise(d.llmCostPaise)],
        ]}
      />
      <div>
        <h3 className="border-b border-ink pb-2 text-14 font-semibold text-ink">Needs attention</h3>
        <dl className="divide-y divide-hairline text-14">
          <div className="flex items-baseline justify-between gap-4 py-2">
            <dt className="text-ink-secondary">Open disputes (any night)</dt>
            <dd className={cx("font-semibold tabular-nums", d.openDisputes > 0 ? "text-error" : "text-ink")}>
              {d.openDisputes}
            </dd>
          </div>
          <div className="grid gap-1 py-2 sm:flex sm:items-baseline sm:justify-between sm:gap-4">
            <dt className="shrink-0 text-ink-secondary">Didn&apos;t answer the ping</dt>
            <dd className="text-ink sm:text-right">{d.noAnswer.length ? d.noAnswer.join(", ") : "Every hotel answered"}</dd>
          </div>
        </dl>
      </div>
    </div>
  );
}

function Group({ title, items }: { title: string; items: [string, string][] }) {
  return (
    <div>
      <h3 className="border-b border-ink pb-2 text-14 font-semibold text-ink">{title}</h3>
      <dl className="grid grid-cols-2 gap-x-6 sm:grid-cols-3 lg:grid-cols-5">
        {items.map(([term, value]) => (
          <div key={term} className="border-b border-hairline py-3">
            <dt className="text-12 text-ink-secondary">{term}</dt>
            <dd className="mt-0.5 text-22 font-semibold text-ink tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function StepButton({ label, children, ...props }: { label: string } & React.ComponentProps<"button">) {
  return (
    <button
      type="button"
      aria-label={label}
      className={cx("inline-flex size-10 items-center justify-center rounded-full text-ink hover:bg-soft disabled:text-hairline", focusRing)}
      {...props}
    >
      <span aria-hidden>{children}</span>
    </button>
  );
}
