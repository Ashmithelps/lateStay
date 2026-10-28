"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Chip } from "@/components/Chip";
import { cx, focusRing } from "@/components/ui";
import type { AdminApi, AdminNegotiation, AdminTurn } from "@/lib/admin-api";
import { formatInstantTime, formatPrice, formatTime } from "@/lib/format";
import { PanelMessage, StatusTag } from "./admin-ui";

/** Live (or recent) negotiations on the left, the selected one's full transcript from negotiation_turn on the right. */
export function NegotiationsPanel({ api }: { api: AdminApi }) {
  const [state, setState] = useState<"live" | "recent">("live");
  const [selected, setSelected] = useState<string | null>(null);
  const list = useQuery({
    queryKey: ["admin", "negotiations", state],
    queryFn: () => api.negotiations(state),
    refetchInterval: 5000,
  });
  const current = selected ?? list.data?.[0]?.id ?? null;

  return (
    <div className="grid gap-6 lg:grid-cols-[380px_1fr]">
      <section aria-label="Negotiations" className="min-w-0">
        <div className="flex gap-2">
          <Chip selected={state === "live"} onClick={() => setState("live")}>
            Live
          </Chip>
          <Chip selected={state === "recent"} onClick={() => setState("recent")}>
            Last 24 hours
          </Chip>
        </div>
        {list.isPending ? (
          <PanelMessage>Loading…</PanelMessage>
        ) : !list.data?.length ? (
          <PanelMessage>{state === "live" ? "Nobody is haggling right now." : "No negotiations in the last day."}</PanelMessage>
        ) : (
          <ul className="mt-3 divide-y divide-hairline border-y border-hairline">
            {list.data.map((n) => (
              <li key={n.id}>
                <button
                  type="button"
                  onClick={() => setSelected(n.id)}
                  aria-current={current === n.id ? "true" : undefined}
                  className={cx(
                    "grid w-full gap-1 px-3 py-3 text-left text-14 transition-colors",
                    current === n.id ? "bg-soft" : "hover:bg-soft",
                    focusRing,
                  )}
                >
                  <span className="flex items-center justify-between gap-3">
                    <span className="truncate font-semibold text-ink">{n.hotelName}</span>
                    <NegotiationStatus n={n} />
                  </span>
                  <span className="text-ink-secondary tabular-nums">
                    {n.roomType} · arrive {formatTime(n.arriveAt)} · {n.turns} turns · offer {formatPrice(n.currentOffer)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-label="Transcript" className="min-w-0">
        {current ? <Transcript api={api} id={current} /> : <PanelMessage>Pick a negotiation to read it.</PanelMessage>}
      </section>
    </div>
  );
}

function NegotiationStatus({ n }: { n: AdminNegotiation }) {
  const tone = n.status === "AGREED" ? "pine" : n.status === "NO_DEAL" ? "error" : "neutral";
  return <StatusTag tone={tone}>{n.status.replace("_", " ").toLowerCase()}</StatusTag>;
}

function Transcript({ api, id }: { api: AdminApi; id: string }) {
  const transcript = useQuery({ queryKey: ["admin", "transcript", id], queryFn: () => api.transcript(id), refetchInterval: 5000 });
  if (!transcript.data) return <PanelMessage>{transcript.error?.message ?? "Loading transcript…"}</PanelMessage>;
  const { negotiation: n, turns } = transcript.data;

  return (
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-2 border-b border-hairline pb-3">
        <h2 className="text-18 font-semibold text-ink">
          {n.hotelName} · {n.roomType}
        </h2>
        <NegotiationStatus n={n} />
      </div>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-1 border-b border-hairline py-3 text-14 tabular-nums sm:grid-cols-4">
        {[
          ["List", formatPrice(n.listPrice)],
          ["Floor", formatPrice(n.floorPrice)],
          ["Offer", formatPrice(n.currentOffer)],
          ["Agreed", n.agreedPrice === null ? "–" : formatPrice(n.agreedPrice)],
          ["Round", `${n.round} of ${n.maxRounds}`],
          ["Guests", String(n.guests)],
          ["Arrive", formatTime(n.arriveAt)],
          ["Expires", formatInstantTime(n.expiresAt)],
        ].map(([term, value]) => (
          <div key={term} className="flex justify-between gap-2 sm:block">
            <dt className="text-12 text-ink-secondary">{term}</dt>
            <dd className="font-medium text-ink">{value}</dd>
          </div>
        ))}
      </dl>
      <ol className="divide-y divide-hairline">
        {turns.map((t) => (
          <li key={t.seq} className="grid gap-1 py-3 text-14 sm:grid-cols-[132px_1fr]">
            <span className="text-12 font-semibold text-ink-secondary">
              {t.role === "BOT" ? "Front desk" : "Traveller"} · {formatInstantTime(t.at)}
            </span>
            <div className="min-w-0">
              <p className="text-ink">{t.text}</p>
              <TurnMeta t={t} />
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

function parse(json: string | null): Record<string, unknown> | null {
  if (!json) return null;
  try {
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function TurnMeta({ t }: { t: AdminTurn }) {
  const intent = parse(t.intent);
  const decision = parse(t.decision);
  const parts = [
    intent && `intent ${String(intent.type)}${intent.amount != null ? ` ${formatPrice(Number(intent.amount))}` : ""}`,
    decision && `engine ${String(decision.action)} ${formatPrice(Number(decision.amount))} (${String(decision.reasonCode)})`,
    t.validatorResult && `reply ${t.validatorResult}`,
    t.latencyMs != null && `${t.latencyMs} ms`,
  ].filter(Boolean);
  if (!parts.length) return null;
  return <p className="mt-1 break-words text-12 text-ink-secondary tabular-nums">{parts.join(" · ")}</p>;
}
