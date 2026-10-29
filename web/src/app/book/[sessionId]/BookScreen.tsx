"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { Button, ButtonLink } from "@/components/Button";
import { DetailRows } from "@/components/DetailRows";
import { Checkbox, TextField } from "@/components/Field";
import { PageHeader } from "@/components/PageHeader";
import { Photo } from "@/components/Photo";
import { api, ApiError, type BookingPreview } from "@/lib/api";
import { saveBooking } from "@/lib/bookings-store";
import { formatDay, formatPrice, formatTime, guestsLabel } from "@/lib/format";
import { normaliseIndianMobile } from "@/lib/phone";
import { useBookingPreview } from "@/lib/queries";
import { pay, PaymentDismissed } from "@/lib/razorpay";

type Errors = { name?: string; phone?: string; consent?: string };

function validate(name: string, phone: string, consent: boolean): Errors {
  const errors: Errors = {};
  if (!name.trim()) errors.name = "Enter the name the booking is under.";
  if (!normaliseIndianMobile(phone)) errors.phone = "Enter a 10-digit Indian mobile number.";
  if (!consent) errors.consent = "The hotel checks a photo ID for every guest.";
  return errors;
}

/** Guest details and the deposit: one ember "Pay ₹200" button that opens Razorpay Checkout. */
export function BookScreen({ initial }: { initial?: BookingPreview }) {
  const { sessionId } = useParams<{ sessionId: string }>();
  const router = useRouter();
  const preview = useBookingPreview(sessionId, initial);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [consent, setConsent] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [paying, setPaying] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const p = preview.data;

  // Already paid for: the pass is the page now.
  const existing = p?.booking;
  useEffect(() => {
    if (existing && existing.status !== "PENDING_PAYMENT") router.replace(`/pass/${existing.id}`);
  }, [existing, router]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!p) return;
    const found = validate(name, phone, consent);
    setErrors(found);
    if (Object.keys(found).length) return;
    setPaying(true);
    setProblem(null);
    try {
      const created = await api.createBooking({
        sessionId,
        guestName: name.trim(),
        phone,
        idConsent: true,
      });
      saveBooking({
        id: created.bookingId,
        sessionId,
        hotelName: p.hotel.name,
        savedAt: Date.now(),
      });
      if (created.checkout) {
        const paid = await pay(created.checkout, `${p.hotel.name}, ${p.roomType}`);
        await api.verifyPayment(created.bookingId, paid);
      }
      router.push(`/pass/${created.bookingId}`);
    } catch (e) {
      setPaying(false);
      setProblem(
        e instanceof PaymentDismissed
          ? "Payment wasn't completed. Your room stays held for a few more minutes."
          : e instanceof Error
            ? e.message
            : "Something went wrong. Try again.",
      );
    }
  }

  const back = `/haggle/${sessionId}`;
  return (
    <>
      <PageHeader backHref={back} backLabel="Back to the chat" />
      <main className="mx-auto max-w-[1120px] px-4 pb-16 pt-6 sm:px-6 lg:px-10">
        {preview.isPending ? (
          <div className="h-64 animate-pulse rounded-card bg-soft" aria-busy="true" />
        ) : !p ? (
          <div className="py-12 text-center">
            <h1 className="text-22 font-semibold text-ink">
              {preview.error instanceof ApiError && preview.error.status === 409
                ? "No price agreed yet"
                : "Couldn't load this booking"}
            </h1>
            <ButtonLink href={back} variant="outline" className="mt-6">
              Back to the chat
            </ButtonLink>
          </div>
        ) : (
          <>
            <h1 className="text-26 font-semibold text-ink">Confirm and pay</h1>
            <div className="mt-6 grid gap-8 lg:grid-cols-[1fr_400px] lg:items-start lg:gap-10">
              <section
                aria-labelledby="summary"
                className="lg:order-2 lg:rounded-card lg:border lg:border-hairline lg:p-6"
              >
                <div className="flex items-center gap-4">
                  <Photo src={p.hotel.photo} alt="" sizes="96px" className="size-24 shrink-0 rounded-card" />
                  <div className="min-w-0">
                    <h2 id="summary" className="text-18 font-semibold text-ink">
                      {p.hotel.name}
                    </h2>
                    <p className="text-14 text-ink-secondary">
                      {p.roomType} · {p.hotel.town}
                    </p>
                  </div>
                </div>
                <DetailRows
                  className="mt-4 border-t border-hairline"
                  rows={[
                    {
                      term: "Arrive",
                      value: `${formatTime(p.arriveAt)}, ${formatDay(p.arriveAt)}`,
                    },
                    { term: "Arrive by", value: formatTime(p.arriveBy) },
                    {
                      term: "Leave by",
                      value: `${formatTime(p.leaveAt)}, ${formatDay(p.leaveAt)}`,
                    },
                    { term: "Guests", value: guestsLabel(p.guests) },
                    { term: "Agreed price", value: formatPrice(p.agreedPrice) },
                    {
                      term: "Pay now",
                      value: formatPrice(p.depositAmount),
                      strong: true,
                    },
                    { term: "Pay at desk", value: formatPrice(p.payAtDesk) },
                  ]}
                />
              </section>

              <form onSubmit={submit} noValidate className="lg:order-1 lg:max-w-[560px]">
                <h2 className="text-18 font-semibold text-ink">Your details</h2>
                <div className="mt-4 grid gap-5">
                  <TextField
                    id="name"
                    label="Name"
                    autoComplete="name"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    error={errors.name}
                  />
                  <TextField
                    id="phone"
                    label="Mobile number"
                    prefix="+91"
                    inputMode="tel"
                    autoComplete="tel-national"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    error={errors.phone}
                    hint="We only use it about this booking, for example if the hotel can't take it."
                  />
                  <Checkbox
                    id="consent"
                    checked={consent}
                    onChange={(e) => setConsent(e.target.checked)}
                    error={errors.consent}
                  >
                    I&apos;ll carry a photo ID for every guest
                  </Checkbox>
                </div>
                {problem && (
                  <p role="alert" className="mt-5 text-14 text-error">
                    {problem}
                  </p>
                )}
                <Button type="submit" fullWidth className="mt-6" disabled={paying}>
                  {paying ? "Opening payment…" : `Pay ${formatPrice(p.depositAmount)}`}
                </Button>
                <p className="mt-4 text-14 text-ink-secondary">
                  Free cancellation until the hotel confirms. If it doesn&apos;t confirm within 5 minutes, your{" "}
                  {formatPrice(p.depositAmount)} is refunded automatically.
                </p>
              </form>
            </div>
          </>
        )}
      </main>
    </>
  );
}
