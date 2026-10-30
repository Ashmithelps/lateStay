import { Star } from "lucide-react";
import { cx } from "./ui";

/** A hotel's rating: "New" until it has 3 reviews, then the star average and (optionally) the count. */
export function RatingBadge({ rating, count, showCount, size = 14, className }: {
  rating: number | null;
  count: number;
  showCount?: boolean;
  size?: 14 | 16;
  className?: string;
}) {
  if (rating === null) {
    return (
      <span className={cx("inline-flex shrink-0 items-center text-ink", size === 14 ? "text-14" : "text-16", className)}>
        New
      </span>
    );
  }
  return (
    <span
      className={cx(
        "inline-flex shrink-0 items-center gap-1 text-ink tabular-nums",
        size === 14 ? "text-14" : "text-16",
        className,
      )}
    >
      <Star size={size} strokeWidth={1.5} className="fill-ink" aria-hidden />
      <span className="sr-only">Rated </span>
      {rating.toFixed(1)}
      {showCount && (
        <span className="text-ink-secondary">
          {" "}
          ({count}
          <span className="sr-only"> reviews</span>)
        </span>
      )}
    </span>
  );
}
