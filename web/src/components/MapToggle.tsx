import { List, Map } from "lucide-react";
import { cx, focusRing } from "./ui";

/** Floating ink pill, bottom centre. */
export function MapToggle({ showingMap, onToggle }: { showingMap: boolean; onToggle: () => void }) {
  const Icon = showingMap ? List : Map;
  return (
    <button
      type="button"
      data-fixed
      onClick={onToggle}
      className={cx(
        "fixed bottom-6 left-1/2 z-[1000] inline-flex min-h-12 -translate-x-1/2 items-center gap-2 rounded-full bg-ink px-5",
        "text-14 font-semibold text-canvas shadow-float transition-transform hover:scale-[1.03]",
        focusRing,
      )}
    >
      {showingMap ? "Show list" : "Show map"}
      <Icon size={20} strokeWidth={1.5} aria-hidden />
    </button>
  );
}
