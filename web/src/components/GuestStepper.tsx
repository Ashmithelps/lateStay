import { Minus, Plus } from "lucide-react";
import { cx, focusRing } from "./ui";

const stepButton = cx(
  "inline-flex size-11 items-center justify-center rounded-full border border-ink-secondary text-ink",
  "transition-colors hover:border-ink disabled:cursor-not-allowed disabled:border-hairline disabled:text-hairline",
  focusRing,
);

export function GuestStepper({ value, onChange, min = 1, max = 8 }: {
  value: number;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div>
        <p className="text-16 font-medium text-ink">Guests</p>
        <p className="text-14 text-ink-secondary">Everyone staying in the room</p>
      </div>
      <div className="flex items-center gap-3">
        <button type="button" className={stepButton} onClick={() => onChange(value - 1)} disabled={value <= min} aria-label="Fewer guests">
          <Minus size={20} strokeWidth={1.5} aria-hidden />
        </button>
        <span className="w-6 text-center text-16 tabular-nums" aria-live="polite">
          {value}
        </span>
        <button type="button" className={stepButton} onClick={() => onChange(value + 1)} disabled={value >= max} aria-label="More guests">
          <Plus size={20} strokeWidth={1.5} aria-hidden />
        </button>
      </div>
    </div>
  );
}
