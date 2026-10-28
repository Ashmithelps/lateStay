import Link from "next/link";
import { cx, focusRing } from "./ui";

export function Wordmark({ className }: { className?: string }) {
  return (
    <Link href="/" className={cx("inline-flex min-h-11 items-center rounded-control text-22 font-semibold text-ink", focusRing, className)}>
      LateStay
    </Link>
  );
}
