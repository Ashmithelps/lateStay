"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Button, ButtonLink } from "@/components/Button";
import { ChatMessage } from "@/components/ChatMessage";
import { Composer } from "@/components/Composer";
import { OfferCard } from "@/components/OfferCard";
import { PageHeader } from "@/components/PageHeader";
import { Photo } from "@/components/Photo";
import { TypingDots } from "@/components/TypingDots";
import { api, ApiError, type ConversationReply, type NegotiationView, type SessionStatus } from "@/lib/api";
import { formatPrice } from "@/lib/format";
import { useNegotiation } from "@/lib/queries";
import { useLastSearchHref } from "@/lib/search-state";

const CLOSED: SessionStatus[] = ["NO_DEAL", "EXPIRED"];

export function HaggleScreen() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const router = useRouter();
  const queryClient = useQueryClient();
  const negotiation = useNegotiation(sessionId);

  const [error, setError] = useState<string | null>(null);
  const backHref = useLastSearchHref();
  const endRef = useRef<HTMLLIElement>(null);

  const v: NegotiationView | undefined = negotiation.data;
  const offer = v ? (v.agreedPrice ?? v.currentOffer) : 0;
  const status: SessionStatus = v?.status ?? "OPEN";

  /** The query cache is the single source of truth for the chat. */
  function patch(update: (view: NegotiationView) => NegotiationView) {
    queryClient.setQueryData<NegotiationView>(["negotiation", sessionId], (old) => (old ? update(old) : old));
  }

  function addTravellerMessage(text: string) {
    setError(null);
    patch((view) => ({ ...view, messages: [...view.messages, { role: "TRAVELLER", text }] }));
  }

  function applyReply(reply: ConversationReply) {
    const movedEngine = reply.intent === "COUNTER" || reply.intent === "ASK_BEST";
    patch((view) => ({
      ...view,
      messages: [...view.messages, { role: "BOT", text: reply.reply }],
      currentOffer: reply.offer,
      status: reply.status,
      agreedPrice: reply.status === "AGREED" ? reply.offer : view.agreedPrice,
      round: movedEngine ? view.round + 1 : view.round,
    }));
    if (reply.status === "AGREED") router.push(`/book/${sessionId}`);
  }

  function onError(e: unknown) {
    if (e instanceof ApiError && e.status === 409) {
      negotiation.refetch();
      return;
    }
    setError("That didn't go through. Please try again.");
  }

  const send = useMutation({
    mutationFn: (text: string) => api.sendMessage(sessionId, text),
    onMutate: addTravellerMessage,
    onSuccess: applyReply,
    onError,
  });

  const accept = useMutation({
    mutationFn: () => api.accept(sessionId),
    onMutate: () => addTravellerMessage(`Accept ${formatPrice(offer)}`),
    onSuccess: applyReply,
    onError,
  });

  const busy = send.isPending || accept.isPending;

  const messages = v?.messages ?? [];
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end", behavior: "smooth" });
  }, [messages.length, busy]);

  const title = v ? (
    <div className="min-w-0">
      <p className="truncate text-16 font-semibold text-ink">{v.hotel.name}</p>
      <p className="truncate text-12 text-ink-secondary">
        {v.roomType.name} · {v.hotel.town}
      </p>
    </div>
  ) : null;
  const hotelHref = v ? `/hotels/${v.hotel.slug}?stay=${v.stayRequestId}` : backHref;

  if (negotiation.isError) {
    return (
      <>
        <PageHeader backHref={backHref} backLabel="Back to results" />
        <main className="mx-auto max-w-md px-4 py-16 text-center">
          <h1 className="text-22 font-semibold text-ink">We couldn&apos;t find this chat</h1>
          <ButtonLink href={backHref} variant="outline" className="mt-6">
            Back to results
          </ButtonLink>
        </main>
      </>
    );
  }

  const closed = CLOSED.includes(status);

  return (
    <div className="flex h-dvh flex-col">
      <PageHeader backHref={hotelHref} backLabel="Back to the hotel" title={title} />
      <main className="mx-auto flex min-h-0 w-full max-w-[1120px] flex-1 gap-12 lg:px-10 lg:py-6">
        <section className="flex min-h-0 flex-1 flex-col" aria-label="Chat with the front desk">
          <div className="px-4 pt-3 lg:px-0 lg:pt-0">
            {v ? <OfferCard offer={offer} status={status} round={v.round} maxRounds={v.maxRounds} /> : <div className="h-[86px] animate-pulse rounded-card bg-soft" />}
          </div>

          <ol className="min-h-0 flex-1 space-y-5 overflow-y-auto px-4 py-5 lg:px-0" aria-live="polite">
            {messages.map((m, i) => (
              <li key={i}>
                <ChatMessage from={m.role === "BOT" ? "bot" : "traveller"} avatar={v?.hotel.photo}>
                  {m.text}
                </ChatMessage>
              </li>
            ))}
            {busy && (
              <li>
                <ChatMessage from="bot" avatar={v?.hotel.photo}>
                  <TypingDots />
                </ChatMessage>
              </li>
            )}
            <li ref={endRef} aria-hidden />
          </ol>

          <div className="border-t border-hairline px-4 pb-[max(16px,env(safe-area-inset-bottom))] pt-3 lg:px-0">
            {closed ? (
              <div className="py-2">
                <p className="text-16 font-medium text-ink">
                  {status === "EXPIRED" ? "This chat has timed out." : "We couldn't agree on a price this time."}
                </p>
                <p className="mt-1 text-14 text-ink-secondary">
                  The front desk&apos;s last offer was {formatPrice(offer)}. Other places nearby may still have rooms.
                </p>
                <ButtonLink href={backHref} variant="outline" fullWidth className="mt-4 sm:w-auto">
                  See other rooms
                </ButtonLink>
              </div>
            ) : (
              <div className="space-y-3">
                <Composer offer={offer} disabled={busy || !v} onSend={(text) => send.mutate(text)} />
                {error && <p className="text-14 text-error">{error}</p>}
                <Button fullWidth onClick={() => accept.mutate()} disabled={busy || !v}>
                  Accept {formatPrice(offer)}
                </Button>
              </div>
            )}
          </div>
        </section>

        {v && (
          <aside className="hidden w-[340px] shrink-0 lg:block">
            <Photo src={v.hotel.photo} alt={v.hotel.name} sizes="340px" className="aspect-[20/19] rounded-card" />
            <h2 className="mt-4 text-18 font-semibold text-ink">{v.hotel.name}</h2>
            <p className="text-14 text-ink-secondary">
              {v.roomType.name} · {v.hotel.town}
            </p>
            <p className="mt-4 text-14 text-ink-secondary">
              The price can only come down while you chat, and nothing is booked until you accept.
            </p>
          </aside>
        )}
      </main>
    </div>
  );
}
