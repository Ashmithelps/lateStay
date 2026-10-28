"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { AdminApi, AdminHotel, WhatsAppLogEntry } from "@/lib/admin-api";
import { formatDay, formatInstantTime } from "@/lib/format";
import { PanelMessage, SmallButton, StatusTag } from "./admin-ui";

const TEMPLATE_LABELS: Record<string, string> = {
  nightly_availability: "Availability ping",
  booking_request: "Booking request",
  booking_cancelled: "Cancellation notice",
};

function tone(status: WhatsAppLogEntry["status"]): "neutral" | "pine" | "error" {
  if (status === "FAILED") return "error";
  if (status === "DELIVERED" || status === "READ") return "pine";
  return "neutral";
}

function localDay(instant: string): string {
  return formatDay(new Date(new Date(instant).getTime() + 5.5 * 3600_000).toISOString());
}

/** The hotel's WhatsApp conversation with LateStay, newest first, refreshed every 5 seconds. */
export function WhatsAppLog({ api, hotel }: { api: AdminApi; hotel: AdminHotel }) {
  const queryClient = useQueryClient();
  const log = useQuery({ queryKey: ["admin", "whatsapp", hotel.id], queryFn: () => api.whatsappLog(hotel.id), refetchInterval: 5000 });
  const ping = useMutation({
    mutationFn: () => api.pingAvailability(hotel.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["admin", "whatsapp", hotel.id] }),
  });

  return (
    <section aria-labelledby={`wa-${hotel.id}`} className="border-t border-hairline pt-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 id={`wa-${hotel.id}`} className="text-16 font-semibold text-ink">WhatsApp</h3>
          <p className="text-12 text-ink-secondary">Owner number {hotel.whatsappNumber}</p>
        </div>
        <SmallButton variant="ink" onClick={() => ping.mutate()} disabled={ping.isPending}>
          {ping.isPending ? "Queuing…" : "Send availability ping now"}
        </SmallButton>
      </div>
      {ping.isSuccess && <p className="mt-2 text-14 text-pine">Ping queued; it goes out within a few seconds.</p>}
      {ping.error && (
        <p role="alert" className="mt-2 text-14 text-error">
          {ping.error.message}
        </p>
      )}
      {!log.data ? (
        <PanelMessage>{log.error?.message ?? "Loading messages…"}</PanelMessage>
      ) : log.data.length === 0 ? (
        <PanelMessage>No WhatsApp messages with this hotel yet.</PanelMessage>
      ) : (
        <ol className="mt-3 divide-y divide-hairline border-y border-hairline">
          {log.data.map((m) => (
            <li key={m.id} className="grid gap-1 py-3 text-14 sm:grid-cols-[140px_minmax(0,1fr)_auto] sm:items-start sm:gap-4">
              <span className="text-12 font-semibold text-ink-secondary tabular-nums">
                {m.direction === "IN" ? "Owner" : "LateStay"} · {formatInstantTime(m.at)}, {localDay(m.at)}
              </span>
              <div className="min-w-0">
                {m.template && <p className="text-12 font-medium text-ink-secondary">{TEMPLATE_LABELS[m.template] ?? m.template}</p>}
                <p className="break-words text-ink">{m.summary}</p>
                {m.error && <p className="mt-0.5 break-words text-12 text-ink-secondary">{m.error}</p>}
              </div>
              <StatusTag tone={tone(m.status)}>{m.status.replace("_", " ").toLowerCase()}</StatusTag>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
