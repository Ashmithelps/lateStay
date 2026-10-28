import Link from "next/link";
import type { ComponentProps, ReactNode } from "react";
import { cx, focusRing } from "./ui";

type Variant = "primary" | "ink" | "outline" | "ghost";

const VARIANTS: Record<Variant, string> = {
  // The one primary (ember) button per screen.
  primary: "bg-ember-button text-canvas hover:bg-ember-button-hover disabled:bg-ember-button/60",
  ink: "bg-ink text-canvas hover:bg-ink/85 disabled:bg-ink/50",
  outline: "border border-ink bg-canvas text-ink hover:bg-soft disabled:border-hairline disabled:text-ink-secondary",
  ghost: "bg-transparent text-ink underline-offset-4 hover:underline disabled:text-ink-secondary",
};

type Common = { variant?: Variant; fullWidth?: boolean; children: ReactNode; className?: string };

function classes({ variant = "primary", fullWidth, className }: Omit<Common, "children">) {
  return cx(
    "inline-flex min-h-12 items-center justify-center gap-2 rounded-control px-5 text-16 font-semibold",
    "transition-colors disabled:cursor-not-allowed",
    VARIANTS[variant],
    fullWidth && "w-full",
    focusRing,
    className,
  );
}

export function Button({ variant, fullWidth, className, children, ...props }: Common & ComponentProps<"button">) {
  return (
    <button type="button" className={classes({ variant, fullWidth, className })} {...props}>
      {children}
    </button>
  );
}

export function ButtonLink({
  variant,
  fullWidth,
  className,
  children,
  ...props
}: Common & ComponentProps<typeof Link>) {
  return (
    <Link className={classes({ variant, fullWidth, className })} {...props}>
      {children}
    </Link>
  );
}
