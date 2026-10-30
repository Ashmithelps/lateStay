"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Star } from "lucide-react";
import { useParams } from "next/navigation";
import { useState } from "react";
import { Button, ButtonLink } from "@/components/Button";
import { PageHeader } from "@/components/PageHeader";
import { cx, focusRing } from "@/components/ui";
import { api, ApiError, type ReviewState } from "@/lib/api";
import { formatDay } from "@/lib/format";
import { MAX_REVIEW_TEXT, REVIEW_TAGS, type ReviewTagId, reviewTextProblem, tagLabel } from "@/lib/reviews";

const STAR_WORDS = ["", "Poor", "Not great", "OK", "Good", "Great"];

export function ReviewScreen({ initial }: { initial?: ReviewState }) {
  const { bookingId } = useParams<{ bookingId: string }>();
  const state = useQuery({
    queryKey: ["review", bookingId],
    queryFn: () => api.reviewState(bookingId),
    initialData: initial,
    retry: 1,
  });
  const passHref = `/pass/${bookingId}`;
  const s = state.data;

  return (
    <>
      <PageHeader backHref={passHref} backLabel="Back to your pass" maxWidth="max-w-[640px]" />
      <main className="mx-auto max-w-[640px] px-4 pb-16 pt-6 sm:px-6">
        {state.isPending ? (
          <div className="h-64 animate-pulse rounded-card bg-soft" aria-busy="true" />
        ) : !s ? (
          <Message title="We couldn't find this stay" body="Check the link from your pass." passHref={passHref} />
        ) : s.status === "OPEN" ? (
          <ReviewForm bookingId={bookingId} state={s} />
        ) : s.status === "DONE" && s.review ? (
          <Done hotelName={s.hotelName} stars={s.review.stars} tags={s.review.tags} text={s.review.text} passHref={passHref} />
        ) : (
          <Message
            title={
              s.status === "NOT_YET"
                ? "You can rate your stay after you leave"
                : s.status === "CLOSED"
                  ? "Reviews for this stay have closed"
                  : "This stay can't be rated"
            }
            body={
              s.status === "NOT_YET"
                ? `Come back once you've checked out of ${s.hotelName}.`
                : s.status === "CLOSED"
                  ? "Reviews stay open for 14 days after the stay."
                  : "Only stays that happened can be rated."
            }
            passHref={passHref}
          />
        )}
      </main>
    </>
  );
}

function Message({ title, body, passHref }: { title: string; body: string; passHref: string }) {
  return (
    <div className="py-12 text-center">
      <h1 className="text-22 font-semibold text-ink">{title}</h1>
      <p className="mt-2 text-16 text-ink-secondary">{body}</p>
      <ButtonLink href={passHref} variant="outline" className="mt-6">
        Back to your pass
      </ButtonLink>
    </div>
  );
}

function ReviewForm({ bookingId, state }: { bookingId: string; state: ReviewState }) {
  const queryClient = useQueryClient();
  const [stars, setStars] = useState(0);
  const [tags, setTags] = useState<ReviewTagId[]>([]);
  const [text, setText] = useState("");
  const [tried, setTried] = useState(false);
  const textProblem = reviewTextProblem(text);
  const length = [...text.trim()].length;

  const submit = useMutation({
    mutationFn: () => api.submitReview(bookingId, { stars, tags, text: text.trim() || null }),
    onSuccess: (review) =>
      queryClient.setQueryData<ReviewState>(["review", bookingId], { ...state, status: "DONE", review, closesAt: null }),
  });

  function send() {
    setTried(true);
    if (stars === 0 || textProblem) return;
    submit.mutate();
  }

  const serverError =
    submit.error instanceof ApiError ? submit.error.message : submit.isError ? "That didn't go through. Try again." : null;

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        send();
      }}
      noValidate
    >
      <h1 className="text-26 font-semibold text-ink">How was {state.hotelName}?</h1>
      <p className="mt-2 text-16 text-ink-secondary">
        Your rating helps the next late arrival choose. It shows without your name
        {state.closesAt ? `; you can leave it until ${formatDay(state.closesAt)}` : ""}.
      </p>

      <fieldset className="mt-8">
        <legend className="text-16 font-semibold text-ink">Your rating</legend>
        <div className="mt-3 flex items-center gap-1" role="radiogroup" aria-label="Stars">
          {[1, 2, 3, 4, 5].map((n) => (
            <label key={n} className={cx("inline-flex size-12 cursor-pointer items-center justify-center rounded-full", "has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ink")}>
              <input
                type="radio"
                name="stars"
                value={n}
                checked={stars === n}
                onChange={() => setStars(n)}
                className="sr-only"
                aria-label={`${n} ${n === 1 ? "star" : "stars"}: ${STAR_WORDS[n]}`}
              />
              <Star
                size={32}
                strokeWidth={1.5}
                aria-hidden
                className={cx("transition-colors", n <= stars ? "fill-ink stroke-ink" : "fill-transparent stroke-ink-secondary")}
              />
            </label>
          ))}
          <span className="ml-3 text-16 text-ink" aria-live="polite">
            {stars > 0 ? STAR_WORDS[stars] : ""}
          </span>
        </div>
        {tried && stars === 0 && <p className="mt-2 text-14 text-error">Choose from 1 to 5 stars.</p>}
      </fieldset>

      <fieldset className="mt-8">
        <legend className="text-16 font-semibold text-ink">What stood out?</legend>
        <p className="mt-1 text-14 text-ink-secondary">Optional. Pick any that were true.</p>
        <div className="mt-3 flex flex-wrap gap-2">
          {REVIEW_TAGS.map((t) => {
            const on = tags.includes(t.id);
            return (
              <button
                key={t.id}
                type="button"
                aria-pressed={on}
                onClick={() => setTags(on ? tags.filter((x) => x !== t.id) : [...tags, t.id])}
                className={cx(
                  "inline-flex min-h-11 items-center gap-2 rounded-full border px-4 text-14 font-medium transition-colors",
                  on ? "border-ink bg-ink text-canvas" : "border-soft bg-soft text-ink hover:border-ink",
                  focusRing,
                )}
              >
                {on && <Check size={16} strokeWidth={1.5} aria-hidden />}
                {t.label}
              </button>
            );
          })}
        </div>
      </fieldset>

      <div className="mt-8">
        <label htmlFor="review-text" className="text-16 font-semibold text-ink">
          Anything else?
        </label>
        <p id="review-text-hint" className="mt-1 text-14 text-ink-secondary">
          Optional. Please leave out names and phone numbers.
        </p>
        <textarea
          id="review-text"
          rows={4}
          value={text}
          onChange={(e) => setText(e.target.value)}
          aria-describedby="review-text-hint review-text-count"
          aria-invalid={tried && textProblem ? true : undefined}
          className={cx(
            "mt-3 block w-full resize-y rounded-control border bg-soft px-4 py-3 text-16 text-ink outline-none",
            "focus:ring-2 focus:ring-ink focus:ring-offset-2 focus:ring-offset-canvas",
            tried && textProblem ? "border-error" : "border-ink-secondary",
          )}
        />
        <div className="mt-1 flex justify-between gap-4 text-14">
          <span className="text-error">{tried ? textProblem : ""}</span>
          <span id="review-text-count" className={cx("tabular-nums", length > MAX_REVIEW_TEXT ? "text-error" : "text-ink-secondary")}>
            {length}/{MAX_REVIEW_TEXT}
          </span>
        </div>
      </div>

      <Button type="submit" fullWidth className="mt-8" disabled={submit.isPending}>
        {submit.isPending ? "Sending…" : "Send rating"}
      </Button>
      {serverError && (
        <p role="alert" className="mt-3 text-14 text-error">
          {serverError}
        </p>
      )}
    </form>
  );
}

function Done({ hotelName, stars, tags, text, passHref }: {
  hotelName: string;
  stars: number;
  tags: string[];
  text: string | null;
  passHref: string;
}) {
  return (
    <div>
      <p className="text-14 font-medium text-pine">Thank you</p>
      <h1 className="mt-1 text-26 font-semibold text-ink">You rated {hotelName}</h1>
      <p className="mt-4 inline-flex items-center gap-1 text-18 text-ink tabular-nums">
        {[1, 2, 3, 4, 5].map((n) => (
          <Star key={n} size={20} strokeWidth={1.5} aria-hidden className={n <= stars ? "fill-ink stroke-ink" : "stroke-ink-secondary"} />
        ))}
        <span className="sr-only">{stars} stars</span>
      </p>
      {tags.length > 0 && <p className="mt-2 text-16 text-ink-secondary">{tags.map(tagLabel).join(", ")}</p>}
      {text && <p className="mt-3 max-w-prose text-16 text-ink">{text}</p>}
      <ButtonLink href={passHref} variant="outline" className="mt-8">
        Back to your pass
      </ButtonLink>
    </div>
  );
}
