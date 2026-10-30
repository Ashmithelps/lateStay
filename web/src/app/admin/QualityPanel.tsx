"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Star } from "lucide-react";
import { useState, type FormEvent } from "react";
import type { AdminApi, HotelReliability } from "@/lib/admin-api";
import { tagLabel } from "@/lib/reviews";
import { LabelledInput, PanelMessage, SmallButton, StatusTag } from "./admin-ui";

const day = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "Asia/Kolkata" });

/** Reliability (and "Reviewed, show again" for paused hotels), reviews (hide abusive text) and the season calendar. */
export function QualityPanel({ api }: { api: AdminApi }) {
  return (
    <div className="grid min-w-0 gap-10 xl:grid-cols-[1.3fr_1fr]">
      <ReliabilityList api={api} />
      <div className="grid min-w-0 content-start gap-10">
        <Seasons api={api} />
        <Reviews api={api} />
      </div>
    </div>
  );
}

export function reliabilityLine(r: HotelReliability): string {
  const parts = [
    r.confirmRatePercent === null ? "no bookings answered yet" : `${r.confirmRatePercent}% confirmed`,
    `${r.confirmed + r.declined + r.timedOut} answered`,
  ];
  if (r.medianMinutesToConfirm !== null) parts.push(`median ${r.medianMinutesToConfirm} min to confirm`);
  parts.push(`${r.rescues} ${r.rescues === 1 ? "rescue" : "rescues"}`);
  parts.push(`${r.upheldDisputes} upheld ${r.upheldDisputes === 1 ? "dispute" : "disputes"}`);
  return parts.join(" · ");
}

function ReliabilityList({ api }: { api: AdminApi }) {
  const queryClient = useQueryClient();
  const rows = useQuery({ queryKey: ["admin", "reliability"], queryFn: api.reliability });
  const reviewed = useMutation({
    mutationFn: api.reliabilityReviewed,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["admin", "reliability"] }),
  });
  return (
    <section aria-labelledby="reliability-title" className="min-w-0">
      <h2 id="reliability-title" className="text-18 font-semibold text-ink">
        Reliability, last 30 days
      </h2>
      <p className="mt-1 text-12 text-ink-secondary">
        Search ranks by distance band, then this score, then price. A hotel under 70% confirmed (after 3 bookings) or
        with 2 rescues and upheld disputes is paused from search until you review it.
      </p>
      {rows.isPending ? (
        <PanelMessage>Loading…</PanelMessage>
      ) : !rows.data?.length ? (
        <PanelMessage>No hotels yet.</PanelMessage>
      ) : (
        <ul className="mt-3 divide-y divide-hairline border-y border-hairline">
          {rows.data.map(({ hotelId, name, town, active, reliability: r }) => (
            <li key={hotelId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2.5 text-14">
              <div className="min-w-0">
                <p className="font-semibold text-ink">
                  {name} <span className="font-normal text-ink-secondary">· {town}</span>
                </p>
                <p className="text-12 text-ink-secondary tabular-nums">{reliabilityLine(r)}</p>
              </div>
              <div className="flex items-center gap-2">
                {!active && <StatusTag>Inactive</StatusTag>}
                {r.pausedAt ? (
                  <>
                    <StatusTag tone="error">Paused {day.format(new Date(r.pausedAt))}</StatusTag>
                    <SmallButton onClick={() => reviewed.mutate(hotelId)} disabled={reviewed.isPending}>
                      Reviewed, show again
                    </SmallButton>
                  </>
                ) : (
                  <StatusTag tone={r.isNew ? "neutral" : r.score >= 80 ? "pine" : "neutral"}>
                    {r.isNew ? "New" : `Score ${r.score}`}
                  </StatusTag>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Seasons({ api }: { api: AdminApi }) {
  const queryClient = useQueryClient();
  const seasons = useQuery({ queryKey: ["admin", "seasons"], queryFn: api.seasons });
  const [draft, setDraft] = useState({ town: "", label: "", startsOn: "", endsOn: "", percent: "120" });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["admin", "seasons"] });
  const create = useMutation({
    mutationFn: () =>
      api.createSeason({
        town: draft.town,
        label: draft.label,
        startsOn: draft.startsOn,
        endsOn: draft.endsOn,
        multiplierPercent: Number(draft.percent),
      }),
    onSuccess: () => {
      setDraft((d) => ({ ...d, label: "", startsOn: "", endsOn: "" }));
      refresh();
    },
  });
  const remove = useMutation({ mutationFn: api.deleteSeason, onSuccess: refresh });
  const set = (key: keyof typeof draft) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setDraft((d) => ({ ...d, [key]: e.target.value }));

  return (
    <section aria-labelledby="seasons-title" className="min-w-0">
      <h2 id="seasons-title" className="text-18 font-semibold text-ink">
        Season calendar
      </h2>
      <p className="mt-1 text-12 text-ink-secondary">
        Opening offers in a town are multiplied for these nights (with the weekend factor, 70–150% in all). Floors
        never move.
      </p>
      <form
        className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          create.mutate();
        }}
      >
        <LabelledInput label="Town" value={draft.town} onChange={set("town")} placeholder="Manali" />
        <LabelledInput label="Label" value={draft.label} onChange={set("label")} maxLength={60} placeholder="Diwali week" />
        <LabelledInput label="Multiplier (%)" inputMode="numeric" value={draft.percent} onChange={set("percent")} />
        <LabelledInput label="First night" type="date" value={draft.startsOn} onChange={set("startsOn")} />
        <LabelledInput label="Last night" type="date" value={draft.endsOn} onChange={set("endsOn")} />
        <SmallButton
          type="submit"
          variant="ink"
          className="self-end"
          disabled={create.isPending || !draft.town || !draft.label || !draft.startsOn || !draft.endsOn}
        >
          Add season
        </SmallButton>
      </form>
      {create.error && (
        <p role="alert" className="mt-2 text-12 text-error">
          {create.error.message}
        </p>
      )}
      {seasons.data && seasons.data.length > 0 && (
        <ul className="mt-4 divide-y divide-hairline border-y border-hairline">
          {seasons.data.map((s) => (
            <li key={s.id} className="flex items-center justify-between gap-4 py-2.5 text-14">
              <div className="min-w-0">
                <p className="font-semibold text-ink">
                  {s.label} <span className="font-normal text-ink-secondary">· {s.town}</span>
                </p>
                <p className="text-12 text-ink-secondary tabular-nums">
                  {day.format(new Date(`${s.startsOn}T12:00:00+05:30`))} – {day.format(new Date(`${s.endsOn}T12:00:00+05:30`))} ·{" "}
                  {s.multiplierPercent}%
                </p>
              </div>
              <SmallButton variant="ghost" onClick={() => remove.mutate(s.id)} disabled={remove.isPending}>
                Remove
              </SmallButton>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Reviews({ api }: { api: AdminApi }) {
  const queryClient = useQueryClient();
  const reviews = useQuery({ queryKey: ["admin", "reviews"], queryFn: api.reviews });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["admin", "reviews"] });
  const hide = useMutation({ mutationFn: api.hideReviewText, onSuccess: refresh });
  const show = useMutation({ mutationFn: api.showReviewText, onSuccess: refresh });
  return (
    <section aria-labelledby="reviews-title" className="min-w-0">
      <h2 id="reviews-title" className="text-18 font-semibold text-ink">
        Reviews
      </h2>
      <p className="mt-1 text-12 text-ink-secondary">Hiding takes the text off the hotel page; the stars still count.</p>
      {reviews.isPending ? (
        <PanelMessage>Loading…</PanelMessage>
      ) : !reviews.data?.length ? (
        <PanelMessage>No reviews yet.</PanelMessage>
      ) : (
        <ul className="mt-3 divide-y divide-hairline border-y border-hairline">
          {reviews.data.map((r) => (
            <li key={r.id} className="grid gap-1 py-2.5 text-14">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="flex items-center gap-1.5 font-semibold text-ink">
                  <Star size={14} strokeWidth={1.5} className="fill-ink" aria-hidden />
                  <span className="tabular-nums">{r.stars}</span>
                  <span className="font-normal text-ink-secondary">
                    · {r.hotelName} · {day.format(new Date(r.createdAt))}
                  </span>
                </p>
                {r.text &&
                  (r.textHidden ? (
                    <SmallButton variant="ghost" onClick={() => show.mutate(r.id)} disabled={show.isPending}>
                      Show text
                    </SmallButton>
                  ) : (
                    <SmallButton variant="ghost" onClick={() => hide.mutate(r.id)} disabled={hide.isPending}>
                      Hide text
                    </SmallButton>
                  ))}
              </div>
              {r.tags.length > 0 && <p className="text-12 text-ink-secondary">{r.tags.map(tagLabel).join(", ")}</p>}
              {r.text && (
                <p className={r.textHidden ? "text-ink-secondary line-through" : "text-ink"}>
                  {r.text}
                  {r.textHidden && <span className="sr-only"> (hidden)</span>}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
