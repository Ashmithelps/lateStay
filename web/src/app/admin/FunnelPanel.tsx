"use client";

import { useQuery } from "@tanstack/react-query";
import { ChevronDown } from "lucide-react";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { cx, focusRing } from "@/components/ui";
import type { AdminApi } from "@/lib/admin-api";
import { formatPaise } from "@/lib/format";
import { PanelMessage } from "./admin-ui";

const LABELS: Record<string, string> = {
  search_started: "Searched",
  results_viewed: "Saw results",
  hotel_opened: "Opened a hotel",
  haggle_started: "Started haggling",
  offer_accepted: "Agreed a price",
  code_sent: "Asked for a code",
  code_verified: "Verified number",
  payment_started: "Started paying",
  payment_succeeded: "Paid",
  hotel_confirmed: "Hotel confirmed",
  checked_in: "Checked in",
};

const percent = new Intl.NumberFormat("en-IN", { style: "percent", maximumFractionDigits: 0 });
const count = new Intl.NumberFormat("en-IN");

/** Visitors reaching each step, with drop-off, for 7 or 30 days; by town and UTM source. */
export function FunnelPanel({ api }: { api: AdminApi }) {
  const [days, setDays] = useState<7 | 30>(7);
  const [town, setTown] = useState("");
  const [source, setSource] = useState("");
  const funnel = useQuery({
    queryKey: ["admin", "funnel", days, town, source],
    queryFn: () => api.funnel(days, town || undefined, source || undefined),
  });
  const f = funnel.data;
  const top = f?.steps[0]?.visitors ?? 0;

  const select = cx(
    "h-10 w-full min-w-0 appearance-none rounded-control border border-ink-secondary bg-soft pl-3 pr-9 text-14 text-ink",
    focusRing,
  );
  const chevron = (
    <ChevronDown size={16} strokeWidth={1.5} className="pointer-events-none absolute right-3 top-3 text-ink-secondary" aria-hidden />
  );
  return (
    <section aria-labelledby="funnel-title" className="min-w-0 max-w-[960px]">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="funnel-title" className="mr-auto text-18 font-semibold text-ink">
          Funnel
        </h2>
        <Chip selected={days === 7} onClick={() => setDays(7)}>
          7 days
        </Chip>
        <Chip selected={days === 30} onClick={() => setDays(30)}>
          30 days
        </Chip>
      </div>
      <div className="mt-3 flex flex-wrap gap-3">
        <label className="grid min-w-0 flex-1 gap-1 text-12 font-medium text-ink-secondary sm:max-w-[240px]">
          Town
          <span className="relative">
            <select value={town} onChange={(e) => setTown(e.target.value)} className={select}>
              <option value="">All towns</option>
              {f?.towns.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
            {chevron}
          </span>
        </label>
        <label className="grid min-w-0 flex-1 gap-1 text-12 font-medium text-ink-secondary sm:max-w-[240px]">
          UTM source
          <span className="relative">
            <select value={source} onChange={(e) => setSource(e.target.value)} className={select}>
              <option value="">All sources</option>
              {f?.sources.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
            {chevron}
          </span>
        </label>
      </div>

      {funnel.isPending ? (
        <PanelMessage>Loading…</PanelMessage>
      ) : funnel.error ? (
        <PanelMessage>{funnel.error.message}</PanelMessage>
      ) : f && top === 0 ? (
        <PanelMessage>No searches in the last {days} days{town || source ? " for this filter" : ""}.</PanelMessage>
      ) : f ? (
        <>
          <ol className="mt-5 divide-y divide-hairline border-y border-hairline">
            {f.steps.map((step, i) => {
              const prev = i === 0 ? step.visitors : f.steps[i - 1].visitors;
              const kept = prev > 0 ? step.visitors / prev : 0;
              return (
                <li key={step.name} className="grid grid-cols-[minmax(0,1fr)_auto_64px] items-center gap-x-4 gap-y-1.5 py-2.5 text-14 sm:grid-cols-[180px_minmax(0,1fr)_72px_96px]">
                  <span className="text-ink">{LABELS[step.name] ?? step.name}</span>
                  <span className="order-last col-span-3 h-2 rounded-full bg-soft sm:order-none sm:col-span-1" aria-hidden>
                    <span className="block h-2 rounded-full bg-ink" style={{ width: `${top ? (step.visitors / top) * 100 : 0}%` }} />
                  </span>
                  <span className="text-right font-semibold text-ink tabular-nums">{count.format(step.visitors)}</span>
                  <span className="text-right text-12 text-ink-secondary tabular-nums">
                    {i === 0 ? "" : `${percent.format(1 - kept)} drop`}
                  </span>
                </li>
              );
            })}
          </ol>
          <dl className="mt-6 grid grid-cols-2 gap-x-6 sm:grid-cols-3">
            <div className="border-b border-hairline py-3">
              <dt className="text-12 text-ink-secondary">Completed bookings</dt>
              <dd className="mt-0.5 text-22 font-semibold text-ink tabular-nums">{count.format(f.completedBookings)}</dd>
            </div>
            <div className="border-b border-hairline py-3">
              <dt className="text-12 text-ink-secondary">LLM spend</dt>
              <dd className="mt-0.5 text-22 font-semibold text-ink tabular-nums">{formatPaise(f.llmCostPaise)}</dd>
            </div>
            <div className="border-b border-hairline py-3">
              <dt className="text-12 text-ink-secondary">LLM per completed booking</dt>
              <dd className="mt-0.5 text-22 font-semibold text-ink tabular-nums">
                {f.llmCostPerBookingPaise === null ? "—" : formatPaise(f.llmCostPerBookingPaise)}
              </dd>
            </div>
          </dl>
          <p className="mt-2 text-12 text-ink-secondary">
            Each step counts different visitors. Completed means checked in or stayed. LLM cost in ₹ at the backend&apos;s
            USD_INR_RATE.
          </p>
        </>
      ) : null}
    </section>
  );
}
