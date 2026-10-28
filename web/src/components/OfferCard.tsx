import type { SessionStatus } from "@/lib/api";
import { formatPrice } from "@/lib/format";

/** The current offer, pinned at the top of the chat. */
export function OfferCard({ offer, status, round, maxRounds }: {
  offer: number;
  status: SessionStatus;
  round: number;
  maxRounds: number;
}) {
  const final = status === "FINAL_OFFER";
  return (
    <div className="flex items-center justify-between gap-3 rounded-card border border-hairline bg-canvas px-4 py-3" aria-live="polite">
      <div>
        <p className="text-14 text-ink-secondary">{status === "AGREED" ? "Agreed price" : status === "NO_DEAL" || status === "EXPIRED" ? "Last offer" : "Current offer"}</p>
        <p className="text-26 font-semibold tabular-nums text-ink">{formatPrice(offer)}</p>
      </div>
      <div className="text-right">
        {final ? (
          <span className="inline-flex items-center rounded-full border border-ink px-3 py-1 text-12 font-semibold text-ink">
            Final offer
          </span>
        ) : (
          status === "OPEN" && (
            <span className="text-14 text-ink-secondary">
              Round {Math.min(round + 1, maxRounds)} of {maxRounds}
            </span>
          )
        )}
      </div>
    </div>
  );
}
