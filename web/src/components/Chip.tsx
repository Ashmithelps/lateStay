import type { LucideIcon } from "lucide-react";
import type { ComponentProps } from "react";
import { cx, focusRing } from "./ui";

type ChipProps = ComponentProps<"button"> & { icon?: LucideIcon; selected?: boolean };

/** Full-pill chip on the soft surface. Toggle chips pass `selected` and get aria-pressed. */
export function Chip({ icon: Icon, selected, className, children, ...props }: ChipProps) {
  return (
    <button
      type="button"
      aria-pressed={selected === undefined ? undefined : selected}
      className={cx(
        "inline-flex min-h-11 shrink-0 items-center gap-2 rounded-full border px-4 text-14 font-medium",
        "transition-colors disabled:cursor-not-allowed disabled:opacity-50",
        selected
          ? "border-ink bg-ink text-canvas"
          : "border-soft bg-soft text-ink hover:border-ink",
        focusRing,
        className,
      )}
      {...props}
    >
      {Icon && <Icon size={20} strokeWidth={1.5} aria-hidden />}
      {children}
    </button>
  );
}
