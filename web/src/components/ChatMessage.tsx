import type { ReactNode } from "react";
import { Photo } from "./Photo";
import { cx } from "./ui";

/** Bot messages: plain text beside a small round hotel photo. Traveller messages: soft-grey pills on the right. */
export function ChatMessage({ from, avatar, children }: { from: "bot" | "traveller"; avatar?: string | null; children: ReactNode }) {
  if (from === "traveller") {
    return (
      <div className="flex justify-end animate-fade-in">
        <p className="max-w-[80%] rounded-[20px] bg-soft px-4 py-2.5 text-16 text-ink">{children}</p>
      </div>
    );
  }
  return (
    <div className="flex items-start gap-3 animate-fade-in">
      <Photo src={avatar} alt="" sizes="32px" className={cx("size-8 shrink-0 rounded-full")} />
      <div className="max-w-[80%] pt-1 text-16 text-ink">{children}</div>
    </div>
  );
}
