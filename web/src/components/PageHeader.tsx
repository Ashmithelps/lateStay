import { ChevronLeft } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";
import { Wordmark } from "./Wordmark";
import { cx, focusRing } from "./ui";

/** Header for inner pages: a back link, an optional title, and the wordmark. */
export function PageHeader({ backHref, backLabel, title, maxWidth = "max-w-[1120px]" }: {
  backHref: string;
  backLabel: string;
  title?: ReactNode;
  maxWidth?: string;
}) {
  return (
    <header className="sticky top-0 z-[1001] border-b border-hairline bg-canvas">
      <div className={cx("mx-auto flex min-h-16 w-full items-center gap-2 px-2 sm:px-6 lg:px-10", maxWidth)}>
        <Link
          href={backHref}
          aria-label={backLabel}
          className={cx("inline-flex size-11 shrink-0 items-center justify-center rounded-full hover:bg-soft", focusRing)}
        >
          <ChevronLeft size={24} strokeWidth={1.5} aria-hidden />
        </Link>
        <div className="min-w-0 flex-1">{title}</div>
        <Wordmark className="shrink-0" />
      </div>
    </header>
  );
}
