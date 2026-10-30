"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import type { AdminApi, AdminSettlement } from "@/lib/admin-api";
import { formatDate, formatInstantTime, formatPaise } from "@/lib/format";
import { inputClass, PanelMessage, SmallButton, StatusTag } from "./admin-ui";

const TYPE_LABELS = {
  DEPOSIT_COLLECTED: "Deposit",
  COMMISSION: "Commission",
  REFUND: "Refund",
  PAYOUT: "Payout",
  ADJUSTMENT: "Adjustment",
} as const;

/** Weekly settlements (made Mondays 10:00 IST): pay by UPI, record the reference, download the CSV. */
export function PayoutsPanel({ api }: { api: AdminApi }) {
  const [status, setStatus] = useState<"DUE" | "PAID" | undefined>("DUE");
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ["admin", "settlements", status], queryFn: () => api.settlements(status) });
  const run = useMutation({
    mutationFn: api.runSettlements,
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ["admin", "settlements"] }),
  });

  return (
    <section aria-label="Payouts" className="min-w-0">
      <div className="flex flex-wrap items-center gap-2">
        <Chip selected={status === "DUE"} onClick={() => setStatus("DUE")}>
          To pay
        </Chip>
        <Chip selected={status === "PAID"} onClick={() => setStatus("PAID")}>
          Paid
        </Chip>
        <Chip selected={status === undefined} onClick={() => setStatus(undefined)}>
          All
        </Chip>
        <SmallButton variant="ghost" className="ml-auto" onClick={() => run.mutate()} disabled={run.isPending}>
          {run.isPending ? "Settling…" : "Settle last week now"}
        </SmallButton>
      </div>
      {run.data && (
        <p role="status" className="mt-2 text-12 text-ink-secondary">
          {run.data.made === 0 ? "Nothing new to settle." : `${run.data.made} new settlements.`}
        </p>
      )}
      {list.isPending ? (
        <PanelMessage>Loading…</PanelMessage>
      ) : !list.data?.length ? (
        <PanelMessage>
          {status === "DUE" ? "Nothing to pay. Settlements are made on Mondays at 10:00." : "No settlements yet."}
        </PanelMessage>
      ) : (
        <ul className="mt-3 divide-y divide-hairline border-y border-hairline">
          {list.data.map((s) => (
            <SettlementRow key={s.id} s={s} api={api} />
          ))}
        </ul>
      )}
    </section>
  );
}

function SettlementRow({ s, api }: { s: AdminSettlement; api: AdminApi }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [paying, setPaying] = useState(false);
  const [reference, setReference] = useState("");
  const detail = useQuery({ queryKey: ["admin", "settlement", s.id], queryFn: () => api.settlement(s.id), enabled: open });
  const pay = useMutation({
    mutationFn: () => api.markPaid(s.id, reference.trim()),
    onSuccess: () => {
      setPaying(false);
      void queryClient.invalidateQueries({ queryKey: ["admin", "settlements"] });
    },
  });
  const download = useMutation({ mutationFn: () => api.settlementCsv(s.id) });

  return (
    <li className="grid gap-2 py-3 text-14">
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1">
        <div className="min-w-0">
          <p className="truncate font-semibold text-ink">{s.hotelName}</p>
          <p className="text-ink-secondary tabular-nums">
            {formatDate(s.periodStart)} – {formatDate(s.periodEnd)}
            {s.upiReference && ` · UPI ${s.upiReference}`}
            {s.paidAt && ` · paid ${formatInstantTime(s.paidAt)}`}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-16 font-semibold text-ink tabular-nums">{formatPaise(s.amountPaise)}</span>
          <StatusTag tone={s.status === "PAID" ? "pine" : "neutral"}>{s.status === "PAID" ? "Paid" : "To pay"}</StatusTag>
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        <SmallButton variant="ghost" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          {open ? "Hide lines" : "Lines"}
        </SmallButton>
        <SmallButton variant="ghost" onClick={() => download.mutate()} disabled={download.isPending}>
          <Download size={16} strokeWidth={1.5} aria-hidden /> CSV
        </SmallButton>
        {s.status === "DUE" && !paying && (
          <SmallButton variant="ink" onClick={() => setPaying(true)}>
            Mark paid
          </SmallButton>
        )}
      </div>
      {paying && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            pay.mutate();
          }}
        >
          <label className="grid min-w-0 flex-1 gap-1 text-12 font-medium text-ink-secondary">
            UPI reference (UTR) for {formatPaise(s.amountPaise)}
            <input
              className={inputClass}
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              maxLength={64}
              autoFocus
            />
          </label>
          <SmallButton type="submit" variant="ink" disabled={!reference.trim() || pay.isPending}>
            Save
          </SmallButton>
          <SmallButton variant="ghost" onClick={() => setPaying(false)}>
            Cancel
          </SmallButton>
        </form>
      )}
      {(pay.error || download.error) && (
        <p role="alert" className="text-12 text-error">
          {(pay.error ?? download.error)?.message}
        </p>
      )}
      {open && (
        <div className="overflow-x-auto">
          {detail.isPending ? (
            <p className="text-12 text-ink-secondary">Loading…</p>
          ) : (
            <table className="w-full min-w-[480px] text-left text-12 tabular-nums">
              <thead className="text-ink-secondary">
                <tr>
                  <th className="py-1 pr-3 font-medium">Night</th>
                  <th className="py-1 pr-3 font-medium">Code</th>
                  <th className="py-1 pr-3 font-medium">Type</th>
                  <th className="py-1 pr-3 font-medium">Note</th>
                  <th className="py-1 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline text-ink">
                {detail.data?.lines.map((l) => (
                  <tr key={l.entryId}>
                    <td className="py-1 pr-3">{l.nightOf ? formatDate(l.nightOf) : "—"}</td>
                    <td className="py-1 pr-3">{l.bookingCode ?? "—"}</td>
                    <td className="py-1 pr-3">{TYPE_LABELS[l.type]}</td>
                    <td className="py-1 pr-3">{l.note}</td>
                    <td className="py-1 text-right">{formatPaise(l.amountPaise)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </li>
  );
}
