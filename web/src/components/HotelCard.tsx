"use client";

import { Heart, Star } from "lucide-react";
import Link from "next/link";
import { useSyncExternalStore } from "react";
import type { HotelResult } from "@/lib/api";
import { formatDistance, formatPrice, roomsFreeLabel } from "@/lib/format";
import { Photo } from "./Photo";
import { cx, focusRing } from "./ui";

const SAVED_KEY = "latestay:saved";

const SAVED_EVENT = "latestay:saved-changed";

function readSaved(): string[] {
  try {
    return JSON.parse(localStorage.getItem(SAVED_KEY) ?? "[]") as string[];
  } catch {
    return [];
  }
}

function subscribeSaved(onChange: () => void) {
  window.addEventListener(SAVED_EVENT, onChange);
  window.addEventListener("storage", onChange);
  return () => {
    window.removeEventListener(SAVED_EVENT, onChange);
    window.removeEventListener("storage", onChange);
  };
}

/** Hearts are a per-device convenience kept in localStorage. */
function useSaved(id: string): [boolean, () => void] {
  const saved = useSyncExternalStore(subscribeSaved, () => readSaved().includes(id), () => false);
  function toggle() {
    const list = new Set(readSaved());
    if (list.has(id)) list.delete(id);
    else list.add(id);
    try {
      localStorage.setItem(SAVED_KEY, JSON.stringify([...list]));
    } catch {
      // storage unavailable
    }
    window.dispatchEvent(new Event(SAVED_EVENT));
  }
  return [saved, toggle];
}

export function HotelCard({ hotel, href, priority, eager, compact }: {
  hotel: HotelResult;
  href: string;
  priority?: boolean;
  eager?: boolean;
  compact?: boolean;
}) {
  const [saved, toggleSaved] = useSaved(hotel.id);
  return (
    <article className={cx("relative", compact && "flex gap-3")}>
      <Link href={href} className={cx("block rounded-card", focusRing, compact && "w-28 shrink-0")} aria-label={hotel.name}>
        <Photo
          src={hotel.photos[0]}
          alt=""
          sizes={compact ? "112px" : "(min-width: 1280px) 25vw, (min-width: 1024px) 33vw, (min-width: 640px) 50vw, 100vw"}
          priority={priority}
          eager={eager}
          className="aspect-[20/19] rounded-card"
        />
      </Link>
      {!compact && (
        <button
          type="button"
          onClick={toggleSaved}
          aria-pressed={saved}
          aria-label={saved ? `Remove ${hotel.name} from saved` : `Save ${hotel.name}`}
          className={cx("absolute right-2 top-2 inline-flex size-11 items-center justify-center rounded-full", focusRing)}
        >
          <Heart
            size={24}
            strokeWidth={1.5}
            aria-hidden
            className={cx("stroke-canvas transition-colors", saved ? "fill-ember" : "fill-ink/40")}
          />
        </button>
      )}
      <div className={cx(compact ? "min-w-0 flex-1 py-1" : "mt-3")}>
        <div className="flex items-start justify-between gap-3">
          <h2 className="truncate text-16 font-semibold text-ink">
            <Link href={href} className="rounded-control outline-none hover:underline focus-visible:underline">
              {hotel.name}
            </Link>
          </h2>
          {hotel.rating !== null && (
            <span className="inline-flex shrink-0 items-center gap-1 text-14 text-ink tabular-nums">
              <Star size={14} strokeWidth={1.5} className="fill-ink" aria-hidden />
              <span className="sr-only">Rated </span>
              {hotel.rating.toFixed(1)}
            </span>
          )}
        </div>
        <p className="text-14 text-ink-secondary">
          {hotel.town} · {formatDistance(hotel.distanceKm)}
        </p>
        <p className="text-14 font-medium text-pine">{roomsFreeLabel(hotel.roomsFree)}</p>
        <p className="mt-1 text-16 text-ink">
          <span className="font-semibold tabular-nums">from {formatPrice(hotel.fromPrice)}</span> tonight
        </p>
      </div>
    </article>
  );
}

export function HotelCardSkeleton() {
  return (
    <div aria-hidden>
      <div className="aspect-[20/19] animate-pulse rounded-card bg-soft" />
      <div className="mt-3 h-4 w-2/3 animate-pulse rounded-control bg-soft" />
      <div className="mt-2 h-3.5 w-1/2 animate-pulse rounded-control bg-soft" />
      <div className="mt-2 h-3.5 w-1/3 animate-pulse rounded-control bg-soft" />
    </div>
  );
}
