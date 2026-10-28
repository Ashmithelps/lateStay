import type { ComponentProps, ReactNode } from "react";
import { cx, focusRing } from "./ui";

/** A labelled text input with helper or error text. The border is ink-secondary so the input meets 3:1. */
export function TextField({
  id,
  label,
  error,
  hint,
  prefix,
  className,
  ...input
}: { id: string; label: string; error?: string | null; hint?: ReactNode; prefix?: string } & ComponentProps<"input">) {
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1 block text-14 font-medium text-ink">
        {label}
      </label>
      <div
        className={cx(
          "flex h-12 items-center rounded-control border bg-soft text-16 text-ink",
          "focus-within:ring-2 focus-within:ring-ink focus-within:ring-offset-2 focus-within:ring-offset-canvas",
          error ? "border-error" : "border-ink-secondary",
        )}
      >
        {prefix && (
          <span className="border-r border-hairline pl-4 pr-3 text-ink-secondary tabular-nums" aria-hidden>
            {prefix}
          </span>
        )}
        <input
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className="h-full min-w-0 flex-1 rounded-control bg-transparent px-4 outline-none placeholder:text-ink-secondary"
          {...input}
        />
      </div>
      {error ? (
        <p id={`${id}-error`} className="mt-1 text-14 text-error">
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${id}-hint`} className="mt-1 text-14 text-ink-secondary">
            {hint}
          </p>
        )
      )}
    </div>
  );
}

/** A checkbox whose whole row is the tap target (at least 44 px). */
export function Checkbox({
  id,
  children,
  error,
  ...input
}: { id: string; children: ReactNode; error?: string | null } & Omit<ComponentProps<"input">, "type">) {
  return (
    <div>
      <label htmlFor={id} className="flex min-h-11 cursor-pointer items-start gap-3 py-2 text-16 text-ink">
        <input
          id={id}
          type="checkbox"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          className={cx("mt-0.5 size-5 shrink-0 cursor-pointer rounded-[4px] accent-ink", focusRing)}
          {...input}
        />
        <span>{children}</span>
      </label>
      {error && (
        <p id={`${id}-error`} className="text-14 text-error">
          {error}
        </p>
      )}
    </div>
  );
}
