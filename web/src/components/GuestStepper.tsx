import { Minus, Plus } from "lucide-react";
import { cx, focusRing } from "./ui";

const stepButton = cx(
  "inline-flex size-11 items-center justify-center rounded-full border border-ink-secondary text-ink",
  "transition-colors hover:border-ink disabled:cursor-not-allowed disabled:border-hairline disabled:text-hairline",
  focusRing,
);

/** A labelled − n + control. */
export function Stepper({ label, hint, value, onChange, min, max, lessLabel, moreLabel }: {
  label: string;
  hint?: string;
  value: number;
  onChange: (value: number) => void;
  min: number;
  max: number;
  lessLabel: string;
  moreLabel: string;
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div>
        <p className="text-16 font-medium text-ink">{label}</p>
        {hint && <p className="text-14 text-ink-secondary">{hint}</p>}
      </div>
      <div className="flex items-center gap-3">
        <button type="button" className={stepButton} onClick={() => onChange(value - 1)} disabled={value <= min} aria-label={lessLabel}>
          <Minus size={20} strokeWidth={1.5} aria-hidden />
        </button>
        <span className="w-6 text-center text-16 tabular-nums" aria-live="polite">
          {value}
        </span>
        <button type="button" className={stepButton} onClick={() => onChange(value + 1)} disabled={value >= max} aria-label={moreLabel}>
          <Plus size={20} strokeWidth={1.5} aria-hidden />
        </button>
      </div>
    </div>
  );
}

export function GuestStepper({ value, onChange, min = 1, max = 8 }: {
  value: number;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
}) {
  return (
    <Stepper
      label="Guests"
      hint="Everyone staying in the room"
      value={value}
      onChange={onChange}
      min={min}
      max={max}
      lessLabel="Fewer guests"
      moreLabel="More guests"
    />
  );
}
