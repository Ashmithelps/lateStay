"use client";

import { SendHorizontal } from "lucide-react";
import { useState } from "react";
import { formatPrice } from "@/lib/format";
import { quickOffers } from "@/lib/stay";
import { Chip } from "./Chip";
import { cx, focusRing } from "./ui";

export const MAX_MESSAGE = 500;

/** Quick-offer chips and the message input (500 characters, the backend's limit). */
export function Composer({ offer, disabled, onSend }: { offer: number; disabled?: boolean; onSend: (text: string) => void }) {
  const [text, setText] = useState("");

  function submit(value: string) {
    const trimmed = value.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setText("");
  }

  return (
    <div className="space-y-3">
      <div className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4" role="group" aria-label="Quick offers">
        {quickOffers(offer).map((amount) => (
          <Chip key={amount} disabled={disabled} onClick={() => submit(`Can you do ${formatPrice(amount)}?`)}>
            <span className="tabular-nums">{formatPrice(amount)}</span>
          </Chip>
        ))}
        <Chip disabled={disabled} onClick={() => submit("What's your best price?")}>
          Best price?
        </Chip>
      </div>
      <form
        className="flex items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          submit(text);
        }}
      >
        <label htmlFor="message" className="sr-only">
          Message the front desk
        </label>
        <input
          id="message"
          value={text}
          onChange={(e) => setText(e.target.value)}
          maxLength={MAX_MESSAGE}
          aria-describedby={text.length > MAX_MESSAGE - 100 ? "message-count" : undefined}
          autoComplete="off"
          placeholder="Make an offer or ask a question"
          className={cx(
            "h-12 min-w-0 flex-1 rounded-full border border-ink-secondary bg-soft px-5 text-16 text-ink placeholder:text-ink-secondary",
            focusRing,
          )}
        />
        <button
          type="submit"
          disabled={disabled || !text.trim()}
          aria-label="Send"
          className={cx(
            "inline-flex size-12 shrink-0 items-center justify-center rounded-full bg-ink text-canvas transition-colors",
            "hover:bg-ink/85 disabled:cursor-not-allowed disabled:bg-hairline disabled:text-ink-secondary",
            focusRing,
          )}
        >
          <SendHorizontal size={20} strokeWidth={1.5} aria-hidden />
        </button>
      </form>
      {text.length > MAX_MESSAGE - 100 && (
        <p id="message-count" className="px-5 text-12 text-ink-secondary tabular-nums" aria-live="polite">
          {text.length} of {MAX_MESSAGE} characters
        </p>
      )}
    </div>
  );
}
