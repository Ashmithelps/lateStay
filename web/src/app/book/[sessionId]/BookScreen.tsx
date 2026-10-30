"use client";

import { useParams, useRouter } from "next/navigation";
import { Check } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { Button, ButtonLink } from "@/components/Button";
import { DetailRows } from "@/components/DetailRows";
import { Checkbox, TextField } from "@/components/Field";
import { PageHeader } from "@/components/PageHeader";
import { Photo } from "@/components/Photo";
import { cx, focusRing } from "@/components/ui";
import { track } from "@/lib/track";
import { api, ApiError, type BookingPreview, type PromoPreview } from "@/lib/api";
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

/** Phone verification: none yet, a code sent (waiting for it), or verified on this device. */
type PhoneStep = { kind: "unverified" } | { kind: "code"; id: string; resendAt: number } | { kind: "verified" };

/**
 * Guest details, a WhatsApp code to verify the number, the cancellation policy, then one ember "Pay ₹200" button
 * that opens Razorpay Checkout. Until the number is verified the step button is ink, so the screen never has two
 * ember buttons.
 */
export function BookScreen({ initial }: { initial?: BookingPreview }) {
  const { sessionId } = useParams<{ sessionId: string }>();
  const router = useRouter();
  const preview = useBookingPreview(sessionId, initial);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [consent, setConsent] = useState(false);
  // Opt-in, never pre-ticked: booking_confirmed and booking_update on WhatsApp (see /privacy).
  const [whatsappUpdates, setWhatsappUpdates] = useState(false);
  // Promo code: checked with "Apply" (dates and uses); the once-per-phone rule is checked when booking.
  const [promoOpen, setPromoOpen] = useState(false);
  const [promoInput, setPromoInput] = useState("");
  const [promo, setPromo] = useState<PromoPreview | null>(null);
  const [promoError, setPromoError] = useState<string | null>(null);
  const [checkingPromo, setCheckingPromo] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [step, setStep] = useState<PhoneStep>({ kind: "unverified" });
  const [code, setCode] = useState("");
  const [codeError, setCodeError] = useState<string | null>(null);
  const [working, setWorking] = useState(false);
  const [paying, setPaying] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const now = useNow(step.kind === "code");
  const p = preview.data;

  // Already paid for: the pass is the page now.
  const existing = p?.booking;
  useEffect(() => {
    if (existing && existing.status !== "PENDING_PAYMENT") router.replace(`/pass/${existing.id}`);
  }, [existing, router]);

  function changePhone(value: string) {
    setPhone(value);
    setStep({ kind: "unverified" });
    setCode("");
    setCodeError(null);
  }

  /** A number already verified on this device (cookie, 30 days) skips the code. */
  async function checkDevice() {
    if (!normaliseIndianMobile(phone) || step.kind !== "unverified") return;
    try {
      if ((await api.phoneStatus(phone)).verified) setStep({ kind: "verified" });
    } catch {
      // not fatal: the code step still works
    }
  }

  async function sendCode() {
    const found = validate(name, phone, consent);
    setErrors(found);
    if (Object.keys(found).length) return;
    setWorking(true);
    setProblem(null);
    setCodeError(null);
    try {
      const sent = await api.sendCode(phone);
      track("code_sent", {}, { negotiationId: sessionId });
      setStep({ kind: "code", id: sent.id, resendAt: Date.now() + sent.resendAfterSeconds * 1000 });
      setCode("");
    } catch (e) {
      setProblem(e instanceof Error ? e.message : "We couldn't send the code. Try again.");
    } finally {
      setWorking(false);
    }
  }

  async function verifyCode(event: FormEvent) {
    event.preventDefault();
    if (step.kind !== "code") return;
    if (!/^\d{6}$/.test(code)) {
      setCodeError("Enter the 6-digit code from WhatsApp.");
      return;
    }
    setWorking(true);
    setCodeError(null);
    try {
      await api.checkCode(step.id, code);
      track("code_verified", {}, { negotiationId: sessionId });
      setStep({ kind: "verified" });
    } catch (e) {
      setCodeError(e instanceof Error ? e.message : "That didn't work. Try again.");
    } finally {
      setWorking(false);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!p) return;
    const found = validate(name, phone, consent);
    setErrors(found);
    if (Object.keys(found).length) return;
    if (step.kind !== "verified") {
      await sendCode();
      return;
    }
    setPaying(true);
    setProblem(null);
    try {
      const created = await api.createBooking({
        sessionId,
        guestName: name.trim(),
        phone,
        idConsent: true,
        whatsappUpdates,
        promoCode: promo?.code ?? null,
      });
      saveBooking({ id: created.bookingId, sessionId, hotelName: p.hotel.name, savedAt: Date.now() });
      if (created.checkout) {
        track("payment_started", { promo: !!promo }, { negotiationId: sessionId });
        const paid = await pay(created.checkout, `${p.hotel.name}, ${p.roomType}`);
        await api.verifyPayment(created.bookingId, paid);
      }
      router.push(`/pass/${created.bookingId}`);
    } catch (e) {
      setPaying(false);
      if (e instanceof ApiError && e.code === "PHONE_NOT_VERIFIED") setStep({ kind: "unverified" });
      if (e instanceof ApiError && e.code?.startsWith("PROMO_")) {
        // e.g. this phone already used the code: drop it, the traveller can pay the full deposit.
        setPromo(null);
        setPromoError(e.message);
      }
      setProblem(
        e instanceof PaymentDismissed
          ? "Payment wasn't completed. Your room stays held for a few more minutes."
          : e instanceof Error
            ? e.message
            : "Something went wrong. Try again.",
      );
    }
  }

  async function applyPromo() {
    const entered = promoInput.trim();
    if (!entered) return;
    setCheckingPromo(true);
    setPromoError(null);
    try {
      setPromo(await api.promo(sessionId, entered));
    } catch (e) {
      setPromo(null);
      setPromoError(e instanceof Error ? e.message : "That code didn't work.");
    } finally {
      setCheckingPromo(false);
    }
  }

  const payNow = promo ? promo.payNow : (p?.depositAmount ?? 0);
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
                    ...(promo ? [{ term: `Promo ${promo.code}`, value: `−${formatPrice(promo.discount)}` }] : []),
                    {
                      term: "Pay now",
                      value: formatPrice(payNow),
                      strong: true,
                    },
                    { term: "Pay at desk", value: formatPrice(p.payAtDesk) },
                  ]}
                />
              </section>

              <div className="lg:order-1 lg:max-w-[560px]">
                <form onSubmit={submit} noValidate>
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
                      label="Mobile number (WhatsApp)"
                      prefix="+91"
                      inputMode="tel"
                      autoComplete="tel-national"
                      value={phone}
                      onChange={(e) => changePhone(e.target.value)}
                      onBlur={checkDevice}
                      error={errors.phone}
                      hint={
                        step.kind === "verified" ? (
                          <span className="inline-flex items-center gap-1 text-pine">
                            <Check size={16} strokeWidth={1.5} aria-hidden /> Verified on this phone
                          </span>
                        ) : (
                          "We'll send a code on WhatsApp to check it. We only use it about this booking."
                        )
                      }
                    />
                    <Checkbox
                      id="whatsapp-updates"
                      checked={whatsappUpdates}
                      onChange={(e) => setWhatsappUpdates(e.target.checked)}
                    >
                      Send my booking pass and updates on WhatsApp
                      <span className="block text-14 text-ink-secondary">
                        Optional. Only this booking: the pass when the hotel confirms, and a line if it&apos;s
                        cancelled or refunded.
                      </span>
                    </Checkbox>
                    <Checkbox
                      id="consent"
                      checked={consent}
                      onChange={(e) => setConsent(e.target.checked)}
                      error={errors.consent}
                    >
                      I&apos;ll carry a photo ID for every guest
                    </Checkbox>
                    <PromoField
                      open={promoOpen}
                      onOpen={() => setPromoOpen(true)}
                      value={promoInput}
                      onChange={(v) => {
                        setPromoInput(v);
                        setPromoError(null);
                      }}
                      applied={promo}
                      error={promoError}
                      checking={checkingPromo}
                      onApply={applyPromo}
                      onRemove={() => {
                        setPromo(null);
                        setPromoInput("");
                      }}
                    />
                  </div>
                  {step.kind === "unverified" && (
                    <Button type="submit" variant="ink" fullWidth className="mt-6" disabled={working}>
                      {working ? "Sending code…" : "Send code on WhatsApp"}
                    </Button>
                  )}
                  {step.kind === "verified" && (
                    <>
                      <CancellationPolicy deposit={p.depositAmount} />
                      {problem && (
                        <p role="alert" className="mt-5 text-14 text-error">
                          {problem}
                        </p>
                      )}
                      <Button type="submit" fullWidth className="mt-6" disabled={paying}>
                        {paying ? "Opening payment…" : `Pay ${formatPrice(payNow)}`}
                      </Button>
                    </>
                  )}
                  {step.kind !== "verified" && problem && (
                    <p role="alert" className="mt-5 text-14 text-error">
                      {problem}
                    </p>
                  )}
                </form>

                {step.kind === "code" && (
                  <form onSubmit={verifyCode} noValidate className="mt-6 rounded-card bg-soft p-4">
                    <TextField
                      id="code"
                      label="6-digit code from WhatsApp"
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      maxLength={6}
                      value={code}
                      onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                      error={codeError}
                      hint={`Sent to +91 ${phone.replace(/\D/g, "").slice(-10)}. It works for 5 minutes.`}
                      className="[&_div]:bg-canvas"
                      autoFocus
                    />
                    <Button type="submit" variant="ink" fullWidth className="mt-4" disabled={working}>
                      {working ? "Checking…" : "Verify number"}
                    </Button>
                    <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-14">
                      <button
                        type="button"
                        className={cx(
                          "min-h-11 rounded-control text-ink underline-offset-4 hover:underline disabled:text-ink-secondary disabled:no-underline",
                          focusRing,
                        )}
                        onClick={sendCode}
                        disabled={working || now < step.resendAt}
                      >
                        {now < step.resendAt
                          ? `Resend code in ${Math.ceil((step.resendAt - now) / 1000)}s`
                          : "Resend code"}
                      </button>
                      <button
                        type="button"
                        className={cx(
                          "min-h-11 rounded-control text-ink underline-offset-4 hover:underline",
                          focusRing,
                        )}
                        onClick={() => changePhone(phone)}
                      >
                        Change number
                      </button>
                    </div>
                  </form>
                )}

                {step.kind !== "verified" && <CancellationPolicy deposit={p.depositAmount} />}

                <p className="mt-4 text-14 text-ink-secondary">
                  By paying you agree to the{" "}
                  <a href="/terms" className="underline underline-offset-4">
                    terms
                  </a>
                  ,{" "}
                  <a href="/refund-policy" className="underline underline-offset-4">
                    refund policy
                  </a>{" "}
                  and{" "}
                  <a href="/privacy" className="underline underline-offset-4">
                    privacy policy
                  </a>
                  .
                </p>
              </div>
            </div>
          </>
        )}
      </main>
    </>
  );
}

/** Ticks once a second while a code is waiting, for the resend countdown. */
function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [active]);
  return now;
}

/** The refund rules, above the Pay button (the same rules as /refund-policy). */
function CancellationPolicy({ deposit }: { deposit: number }) {
  const amount = formatPrice(deposit);
  return (
    <section aria-labelledby="policy" className="mt-6 border-t border-hairline pt-5">
      <h3 id="policy" className="text-16 font-semibold text-ink">
        Cancellation
      </h3>
      <ul className="mt-2 space-y-2 text-14 text-ink">
        <li>Free cancellation until the hotel confirms: your {amount} comes back in full.</li>
        <li>Once the hotel confirms, the {amount} deposit is non-refundable.</li>
        <li>
          If the hotel declines, doesn&apos;t confirm within 5 minutes or can&apos;t give you the room, you get it all
          back.
        </li>
      </ul>
    </section>
  );
}

/** "Have a promo code?" opens a field with Apply; an applied code shows with Remove. */
function PromoField({
  open,
  onOpen,
  value,
  onChange,
  applied,
  error,
  checking,
  onApply,
  onRemove,
}: {
  open: boolean;
  onOpen: () => void;
  value: string;
  onChange: (value: string) => void;
  applied: PromoPreview | null;
  error: string | null;
  checking: boolean;
  onApply: () => void;
  onRemove: () => void;
}) {
  if (applied) {
    return (
      <p className="flex items-center justify-between gap-3 text-14 text-ink" role="status">
        <span>
          <span className="font-semibold">{applied.code}</span> applied: {formatPrice(applied.discount)} off what you pay
          now.
        </span>
        <button type="button" onClick={onRemove} className={cx("min-h-11 rounded-control px-2 text-ink underline-offset-4 hover:underline", focusRing)}>
          Remove
        </button>
      </p>
    );
  }
  if (!open) {
    return (
      <button
        type="button"
        onClick={onOpen}
        className={cx("min-h-11 justify-self-start rounded-control text-14 text-ink underline underline-offset-4", focusRing)}
      >
        Have a promo code?
      </button>
    );
  }
  return (
    <div>
      <div className="flex items-end gap-3">
        <TextField
          id="promo"
          label="Promo code"
          autoCapitalize="characters"
          autoComplete="off"
          value={value}
          onChange={(e) => onChange(e.target.value.toUpperCase())}
          error={error}
          className="flex-1"
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              onApply();
            }
          }}
        />
        <Button variant="outline" onClick={onApply} disabled={checking || !value.trim()} className={error ? "mb-6" : undefined}>
          {checking ? "Checking…" : "Apply"}
        </Button>
      </div>
    </div>
  );
}
