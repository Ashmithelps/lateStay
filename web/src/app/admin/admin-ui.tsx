import type { ComponentProps, ReactNode } from "react";
import { cx, focusRing } from "@/components/ui";
import type { BookingStatus } from "@/lib/api";

/** Compact controls for the admin panel: same tokens as the traveller app, denser sizes. */
export const inputClass = cx(
  "h-10 w-full min-w-0 rounded-control border border-ink-secondary bg-soft px-3 text-14 text-ink tabular-nums",
  focusRing,
);

export function SmallButton({
  variant = "outline",
  className,
  ...props
}: { variant?: "ink" | "outline" | "ghost" } & ComponentProps<"button">) {
  return (
    <button
      type="button"
      className={cx(
        "inline-flex min-h-10 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-control px-3 text-14 font-semibold transition-colors",
        "disabled:cursor-not-allowed disabled:opacity-50",
        variant === "ink" && "bg-ink text-canvas hover:bg-ink/85",
        variant === "outline" && "border border-ink bg-canvas text-ink hover:bg-soft",
        variant === "ghost" && "text-ink hover:bg-soft",
        focusRing,
        className,
      )}
      {...props}
    />
  );
}

export function LabelledInput({ label, className, ...input }: { label: string } & ComponentProps<"input">) {
  return (
    <label className={cx("grid gap-1 text-12 font-medium text-ink-secondary", className)}>
      {label}
      <input className={inputClass} {...input} />
    </label>
  );
}

const STATUS_LABELS: Record<BookingStatus, string> = {
  PENDING_PAYMENT: "Awaiting payment",
  DEPOSIT_PAID: "Needs confirmation",
  HOTEL_CONFIRMED: "Confirmed",
  CHECKED_IN: "Checked in",
  COMPLETED: "Completed",
  EXPIRED: "Expired",
  HOTEL_DECLINED: "Declined",
  HOTEL_TIMEOUT: "Hotel timed out",
  REFUNDED: "Refunded",
  CANCELLED: "Cancelled",
  NO_SHOW: "No-show",
};

export function statusLabel(status: BookingStatus): string {
  return STATUS_LABELS[status];
}

export function StatusTag({ children, tone = "neutral" }: { children: ReactNode; tone?: "neutral" | "pine" | "error" }) {
  return (
    <span
      className={cx(
        "inline-flex shrink-0 items-center whitespace-nowrap rounded-full border px-2 py-0.5 text-12 font-medium",
        tone === "pine" && "border-pine text-pine",
        tone === "error" && "border-error text-error",
        tone === "neutral" && "border-hairline text-ink-secondary",
      )}
    >
      {children}
    </span>
  );
}

export function PanelMessage({ children }: { children: ReactNode }) {
  return <p className="py-8 text-center text-14 text-ink-secondary">{children}</p>;
}
