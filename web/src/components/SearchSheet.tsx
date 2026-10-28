"use client";

import { Check, ChevronDown, LocateFixed, MapPin, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { formatTime } from "@/lib/format";
import type { SearchState } from "@/lib/search-state";
import { ARRIVAL_TIMES, fitLeave, leaveTimes } from "@/lib/stay";
import { TOWNS } from "@/lib/towns";
import { Button } from "./Button";
import { GuestStepper } from "./GuestStepper";
import type { SearchSection } from "./SearchPill";
import { cx, focusRing } from "./ui";

type Props = {
  initial: SearchState;
  section: SearchSection;
  onClose: () => void;
  onSearch: (search: SearchState) => void;
};

const FOCUSABLE = 'button:not([disabled]), select, input, a[href], [tabindex]:not([tabindex="-1"])';

function TimeSelect({ id, label, value, options, onChange }: {
  id: string;
  label: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="flex-1">
      <label htmlFor={id} className="mb-1 block text-14 font-medium text-ink">
        {label}
      </label>
      <div className="relative">
        <select
          id={id}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className={cx(
            "h-12 w-full appearance-none rounded-control border border-ink-secondary bg-soft pl-4 pr-10 text-16 text-ink",
            focusRing,
          )}
        >
          {options.map((t) => (
            <option key={t} value={t}>
              {formatTime(t)}
            </option>
          ))}
        </select>
        <ChevronDown size={20} strokeWidth={1.5} className="pointer-events-none absolute right-3 top-3.5 text-ink-secondary" aria-hidden />
      </div>
    </div>
  );
}

/**
 * Where / when / guests. A bottom sheet on mobile, a panel under the search pill on desktop. Uses an ink Search
 * button so the pill keeps the screen's only ember button.
 */
/** Render only while open: it starts from `initial` each time it mounts. */
export function SearchSheet({ initial, section, onClose, onSearch }: Props) {
  const [draft, setDraft] = useState(initial);
  const [locating, setLocating] = useState<"idle" | "busy" | "denied">("idle");
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!panel.current) return;
    const root = panel.current;
    const target = root.querySelector<HTMLElement>(`[data-section="${section}"] ${FOCUSABLE}`) ??
      root.querySelector<HTMLElement>(FOCUSABLE);
    target?.focus();

    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const items = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (!items.length) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [section, onClose]);

  function useMyLocation() {
    if (!("geolocation" in navigator)) {
      setLocating("denied");
      return;
    }
    setLocating("busy");
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setLocating("idle");
        setDraft((d) => ({ ...d, place: "here", placeLabel: "Your location", lat: pos.coords.latitude, lng: pos.coords.longitude }));
      },
      () => setLocating("denied"),
      { timeout: 8000, maximumAge: 60_000 },
    );
  }

  const placeRow = (selected: boolean) =>
    cx(
      "flex min-h-12 w-full items-center gap-3 rounded-control px-3 text-left transition-colors",
      selected ? "bg-soft" : "hover:bg-soft",
      focusRing,
    );

  return (
    <div className="fixed inset-0 z-[1100]">
      <button type="button" aria-label="Close search" tabIndex={-1} className="absolute inset-0 bg-ink/40 animate-fade-in" onClick={onClose} />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby="search-sheet-title"
        className={cx(
          "absolute inset-x-0 bottom-0 max-h-[90dvh] overflow-y-auto rounded-t-card bg-canvas px-4 pb-6 pt-3 animate-sheet-up",
          "md:inset-x-auto md:bottom-auto md:left-1/2 md:top-24 md:w-[560px] md:-translate-x-1/2 md:rounded-card md:border md:border-hairline md:p-6",
        )}
      >
        <div className="mx-auto mb-3 h-1 w-10 rounded-full bg-hairline md:hidden" aria-hidden />
        <div className="flex items-center justify-between">
          <h2 id="search-sheet-title" className="text-18 font-semibold text-ink">
            Find a room for tonight
          </h2>
          <button type="button" onClick={onClose} aria-label="Close" className={cx("inline-flex size-11 items-center justify-center rounded-full hover:bg-soft", focusRing)}>
            <X size={20} strokeWidth={1.5} aria-hidden />
          </button>
        </div>

        <section data-section="where" className="mt-4" aria-labelledby="where-title">
          <h3 id="where-title" className="text-14 font-semibold text-ink">
            Where
          </h3>
          <ul className="mt-2 space-y-1">
            <li>
              <button type="button" className={placeRow(draft.place === "here")} onClick={useMyLocation} aria-pressed={draft.place === "here"}>
                <LocateFixed size={20} strokeWidth={1.5} aria-hidden />
                <span className="text-16 text-ink">Use my location</span>
                {locating === "busy" && <span className="ml-auto text-14 text-ink-secondary">Finding you…</span>}
                {draft.place === "here" && locating !== "busy" && <Check size={20} strokeWidth={1.5} className="ml-auto" aria-hidden />}
              </button>
              {locating === "denied" && (
                <p className="px-3 pt-1 text-14 text-error">Location is off or blocked. Pick a town instead.</p>
              )}
            </li>
            {TOWNS.map((town) => (
              <li key={town.id}>
                <button
                  type="button"
                  className={placeRow(draft.place === town.id)}
                  aria-pressed={draft.place === town.id}
                  onClick={() => setDraft((d) => ({ ...d, place: town.id, placeLabel: town.name, lat: town.lat, lng: town.lng }))}
                >
                  <MapPin size={20} strokeWidth={1.5} aria-hidden />
                  <span className="text-16 text-ink">{town.name}</span>
                  <span className="text-14 text-ink-secondary">{town.area}</span>
                  {draft.place === town.id && <Check size={20} strokeWidth={1.5} className="ml-auto" aria-hidden />}
                </button>
              </li>
            ))}
          </ul>
        </section>

        <section data-section="when" className="mt-6 border-t border-hairline pt-5" aria-label="When">
          <div className="flex gap-3">
            <TimeSelect
              id="arrive"
              label="Arrive"
              value={draft.arrive}
              options={ARRIVAL_TIMES}
              onChange={(arrive) => setDraft((d) => ({ ...d, arrive, leave: fitLeave(arrive, d.leave) }))}
            />
            <TimeSelect
              id="leave"
              label="Leave"
              value={draft.leave}
              options={leaveTimes(draft.arrive)}
              onChange={(leave) => setDraft((d) => ({ ...d, leave }))}
            />
          </div>
          <p className="mt-2 text-14 text-ink-secondary">Late-night rooms are for arrivals from 9:00 pm to 3:00 am, early-morning rooms from 4:00 am to 9:00 am.</p>
        </section>

        <section data-section="guests" className="mt-6 border-t border-hairline pt-5">
          <GuestStepper value={draft.guests} onChange={(guests) => setDraft((d) => ({ ...d, guests }))} />
        </section>

        <div className="mt-6 border-t border-hairline pt-5 md:flex md:justify-end">
          <Button variant="ink" fullWidth onClick={() => onSearch(draft)} className="md:w-auto md:min-w-40">
            Search
          </Button>
        </div>
      </div>
    </div>
  );
}
