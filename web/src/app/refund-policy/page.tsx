import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { LEGAL_UPDATED, SUPPORT_EMAIL } from "@/lib/legal";

export const metadata: Metadata = { title: "Refund policy · LateStay" };

/** Exactly the rules the booking code applies (BookingTransitions, BookingService). Change both together. */
export default function RefundPolicyPage() {
  return (
    <LegalPage title="Cancellation and refund policy" updated={LEGAL_UPDATED}>
      <section>
        <p>
          When you book on LateStay you pay a deposit online: ₹200, or the whole price if it&apos;s less than that. You
          pay the rest at the hotel&apos;s desk. These are the rules for getting the deposit back.
        </p>
      </section>

      <section>
        <h2>You get the full deposit back if</h2>
        <ul>
          <li>you cancel before the hotel confirms your booking (the Cancel booking button on your pass);</li>
          <li>the hotel declines the booking;</li>
          <li>the hotel doesn&apos;t confirm within 5 minutes of your payment (we cancel and refund automatically);</li>
          <li>
            the hotel confirms but then can&apos;t give you the room when you arrive. Use &quot;The hotel turned me
            away&quot; on your pass, or message our support on WhatsApp: we refund the deposit straight away and show
            you places with rooms free;
          </li>
          <li>a payment reaches us after your booking had already expired or been cancelled.</li>
        </ul>
      </section>

      <section>
        <h2>The deposit is not refunded if</h2>
        <ul>
          <li>you cancel after the hotel has confirmed. The room is held for you from that moment;</li>
          <li>
            you don&apos;t arrive. An hour after your &quot;arrive by&quot; time we ask the hotel whether you came. If
            the hotel says you didn&apos;t, the deposit goes to the hotel. If you did arrive, tap &quot;I did
            arrive&quot; on your pass (once per booking) and we&apos;ll check with the hotel and settle it;
          </li>
          <li>
            the hotel doesn&apos;t answer us by 10:00 the next morning (at least two hours after we asked). We then
            take it that you stayed.
          </li>
        </ul>
      </section>

      <section>
        <h2>If you don&apos;t pay</h2>
        <p>
          After you agree a price the room is held for 10 minutes, and for 10 minutes more once you start paying. If
          no payment comes through, the booking expires and nothing is charged.
        </p>
      </section>

      <section>
        <h2>How refunds are paid</h2>
        <ul>
          <li>Refunds go back to the card, UPI account or wallet you paid with, through Razorpay.</li>
          <li>We start the refund immediately. Banks usually show it within 5 to 7 working days.</li>
          <li>The amount you pay at the hotel&apos;s desk is between you and the hotel.</li>
        </ul>
      </section>

      <section>
        <h2>Questions</h2>
        <p>
          Write to <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a> with your 4-digit booking code, or see{" "}
          <a href="/contact">Contact</a> for WhatsApp.
        </p>
      </section>
    </LegalPage>
  );
}
