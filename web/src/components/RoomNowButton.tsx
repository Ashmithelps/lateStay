import { Clock } from "lucide-react";
import { cx, focusRing } from "./ui";

/**
 * "I need a room now": an ink pill above the results. Not ember: the search pill's button stays the screen's only
 * ember button.
 */
export function RoomNowButton({ busy, onClick, className }: { busy: boolean; onClick: () => void; className?: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      aria-busy={busy}
      className={cx(
        "inline-flex min-h-12 items-center gap-2 rounded-full bg-ink px-5 text-16 font-semibold text-canvas",
        "transition-colors hover:bg-ink/85 disabled:cursor-wait disabled:bg-ink/70",
        focusRing,
        className,
      )}
    >
      <Clock size={20} strokeWidth={1.5} aria-hidden />
      {busy ? "Finding you…" : "I need a room now"}
    </button>
  );
}
