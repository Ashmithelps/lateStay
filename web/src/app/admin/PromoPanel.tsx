"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import type { AdminApi } from "@/lib/admin-api";
import { formatPaise, formatPrice } from "@/lib/format";
import { LabelledInput, PanelMessage, SmallButton, StatusTag } from "./admin-ui";

const day = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "Asia/Kolkata" });

/**
 * Promo codes: a flat discount off the deposit, whole rupees, LateStay pays it to the hotel. The form warns when
 * the discount is more than LateStay's commission (₹100 by default): each use then costs money.
 */
export function PromoPanel({ api, commission = 100 }: { api: AdminApi; commission?: number }) {
  const queryClient = useQueryClient();
  const promos = useQuery({ queryKey: ["admin", "promos"], queryFn: api.promoCodes });
  const [draft, setDraft] = useState({ code: "", rupees: "50", from: "", until: "", uses: "100" });
  const create = useMutation({
    mutationFn: () =>
      api.createPromo({
        code: draft.code,
        discountPaise: Number(draft.rupees) * 100,
        validFrom: new Date(`${draft.from}T00:00:00+05:30`).toISOString(),
        validUntil: new Date(`${draft.until}T23:59:59+05:30`).toISOString(),
        maxUses: Number(draft.uses),
      }),
    onSuccess: () => {
      setDraft((d) => ({ ...d, code: "" }));
      void queryClient.invalidateQueries({ queryKey: ["admin", "promos"] });
    },
  });
  const deactivate = useMutation({
    mutationFn: api.deactivatePromo,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["admin", "promos"] }),
  });
  const set = (key: keyof typeof draft) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setDraft((d) => ({ ...d, [key]: key === "code" ? e.target.value.toUpperCase() : e.target.value }));
  const overCommission = Number(draft.rupees) > commission;

  return (
    <section aria-labelledby="promo-title" className="min-w-0 max-w-[960px]">
      <h2 id="promo-title" className="text-18 font-semibold text-ink">
        Promo codes
      </h2>
      <form
        className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-[1.4fr_1fr_1fr_1fr_1fr_auto] sm:items-end"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          create.mutate();
        }}
      >
        <LabelledInput label="Code" value={draft.code} onChange={set("code")} maxLength={20} placeholder="REEL50" className="col-span-2 sm:col-span-1" />
        <LabelledInput label="Off the deposit (₹)" inputMode="numeric" value={draft.rupees} onChange={set("rupees")} />
        <LabelledInput label="Total uses" inputMode="numeric" value={draft.uses} onChange={set("uses")} />
        <LabelledInput label="From" type="date" value={draft.from} onChange={set("from")} />
        <LabelledInput label="Until" type="date" value={draft.until} onChange={set("until")} />
        <SmallButton type="submit" variant="ink" disabled={create.isPending || !draft.code || !draft.from || !draft.until}>
          Create
        </SmallButton>
      </form>
      {overCommission && (
        <p className="mt-2 text-12 text-error">
          More than your {formatPrice(commission)} commission: each use costs LateStay{" "}
          {formatPrice(Number(draft.rupees) - commission)} (the hotel is always paid in full).
        </p>
      )}
      {create.error && (
        <p role="alert" className="mt-2 text-12 text-error">
          {create.error.message}
        </p>
      )}
      {promos.isPending ? (
        <PanelMessage>Loading…</PanelMessage>
      ) : !promos.data?.length ? (
        <PanelMessage>No promo codes yet.</PanelMessage>
      ) : (
        <ul className="mt-4 divide-y divide-hairline border-y border-hairline">
          {promos.data.map((p) => (
            <li key={p.id} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2.5 text-14">
              <div className="min-w-0">
                <p className="font-semibold text-ink">{p.code}</p>
                <p className="text-12 text-ink-secondary tabular-nums">
                  {formatPaise(p.discountPaise)} off · {day.format(new Date(p.validFrom))} – {day.format(new Date(p.validUntil))} ·{" "}
                  {p.uses} of {p.maxUses} used
                </p>
              </div>
              <div className="flex items-center gap-2">
                <StatusTag tone={p.active ? "pine" : "neutral"}>{p.active ? "Active" : "Off"}</StatusTag>
                {p.active && (
                  <SmallButton variant="ghost" onClick={() => deactivate.mutate(p.id)} disabled={deactivate.isPending}>
                    Turn off
                  </SmallButton>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
