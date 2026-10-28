import { Search } from "lucide-react";
import { forwardRef } from "react";
import { formatTime } from "@/lib/format";
import type { SearchState } from "@/lib/search-state";
import { cx, focusRing } from "./ui";

export type SearchSection = "where" | "when" | "guests";

type Props = { search: SearchState; onOpen: (section: SearchSection) => void };

const segment = cx("flex min-h-12 flex-col justify-center rounded-full px-5 text-left transition-colors hover:bg-soft", focusRing);

/** The floating search pill: Where, Arrive, Leave, Guests and a round ember search button. */
export const SearchPill = forwardRef<HTMLDivElement, Props>(function SearchPill({ search, onOpen }, ref) {
  const guests = `${search.guests} ${search.guests === 1 ? "guest" : "guests"}`;
  return (
    <div
      ref={ref}
      className="flex w-full items-center rounded-full border border-hairline bg-canvas p-1.5 shadow-float md:w-auto"
    >
      {/* Mobile: one button with a two-line summary. */}
      <button
        type="button"
        onClick={() => onOpen("where")}
        className={cx("flex min-h-12 min-w-0 flex-1 items-center gap-3 rounded-full px-3 text-left md:hidden", focusRing)}
        aria-label={`Search: ${search.placeLabel}, arrive ${formatTime(search.arrive)}, leave ${formatTime(search.leave)}, ${guests}. Change search`}
      >
        <span className="min-w-0">
          <span className="block truncate text-14 font-semibold text-ink">{search.placeLabel}</span>
          <span className="block truncate text-12 text-ink-secondary">
            Tonight {formatTime(search.arrive)} – {formatTime(search.leave)} · {guests}
          </span>
        </span>
      </button>

      {/* Desktop: four segments. */}
      <div className="hidden items-center md:flex">
        <button type="button" className={segment} onClick={() => onOpen("where")}>
          <span className="text-12 font-semibold text-ink">Where</span>
          <span className="text-14 text-ink-secondary">{search.placeLabel}</span>
        </button>
        <span className="h-8 w-px bg-hairline" aria-hidden />
        <button type="button" className={segment} onClick={() => onOpen("when")}>
          <span className="text-12 font-semibold text-ink">Arrive</span>
          <span className="text-14 text-ink-secondary">Tonight, {formatTime(search.arrive)}</span>
        </button>
        <span className="h-8 w-px bg-hairline" aria-hidden />
        <button type="button" className={segment} onClick={() => onOpen("when")}>
          <span className="text-12 font-semibold text-ink">Leave</span>
          <span className="text-14 text-ink-secondary">{formatTime(search.leave)}</span>
        </button>
        <span className="h-8 w-px bg-hairline" aria-hidden />
        <button type="button" className={segment} onClick={() => onOpen("guests")}>
          <span className="text-12 font-semibold text-ink">Guests</span>
          <span className="text-14 text-ink-secondary">{guests}</span>
        </button>
      </div>

      <button
        type="button"
        onClick={() => onOpen("where")}
        aria-label="Search"
        className={cx(
          "ml-1 inline-flex size-12 shrink-0 items-center justify-center rounded-full bg-ember-button text-canvas",
          "transition-colors hover:bg-ember-button-hover",
          focusRing,
        )}
      >
        <Search size={20} strokeWidth={2} aria-hidden />
      </button>
    </div>
  );
});
