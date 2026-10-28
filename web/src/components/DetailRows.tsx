import type { ReactNode } from "react";
import { cx } from "./ui";

export type DetailRow = { term: string; value: ReactNode; strong?: boolean };

/** Label / value rows separated by hairlines (booking summary, booking pass). */
export function DetailRows({ rows, className }: { rows: DetailRow[]; className?: string }) {
  return (
    <dl className={cx("divide-y divide-hairline", className)}>
      {rows.map((row) => (
        <div key={row.term} className="flex items-start justify-between gap-6 py-3">
          <dt className="text-16 text-ink-secondary">{row.term}</dt>
          <dd className={cx("text-right text-16 text-ink tabular-nums", row.strong ? "font-semibold" : "font-medium")}>
            {row.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
