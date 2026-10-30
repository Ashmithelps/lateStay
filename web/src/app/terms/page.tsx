import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { LEGAL_UPDATED, SUPPORT_EMAIL } from "@/lib/legal";

export const metadata: Metadata = { title: "Terms · LateStay" };

export default function TermsPage() {
  return (
    <LegalPage title="Terms" updated={LEGAL_UPDATED}>
      <section>
        <p>
          These terms cover using LateStay to find and book a room for part of the night. By booking, you agree to
          them.
        </p>
      </section>

      <section>
        <h2>What LateStay is</h2>
        <ul>
          <li>LateStay connects travellers who arrive late with hotels that still have rooms. The hotel provides the room.</li>
          <li>
            The front desk you chat with is an automated assistant acting for the hotel. Every price it offers comes from
            limits the hotel has set; it can&apos;t offer less than the hotel allows.
          </li>
          <li>A price is only agreed when you accept it. Until then, offers can change or expire.</li>
        </ul>
      </section>

      <section>
        <h2>Paying and booking</h2>
        <ul>
          <li>
            Before paying, you confirm your mobile number with a code we send on WhatsApp.
          </li>
          <li>
            You pay a deposit online: ₹200, or the whole price if it&apos;s less than that. You pay the rest at the hotel&apos;s
            desk.
          </li>
          <li>If you don&apos;t finish paying within 10 minutes, the room is released and nothing is charged.</li>
          <li>
            The hotel confirms the booking after you pay. If it declines, or doesn&apos;t confirm within 5 minutes, your
            deposit is refunded in full automatically.
          </li>
          <li>Refunds go back to how you paid and usually arrive within 5 to 7 working days.</li>
        </ul>
      </section>

      <section>
        <h2>Cancelling and arriving late</h2>
        <ul>
          <li>You can cancel for a full refund until the hotel confirms.</li>
          <li>Once the hotel has confirmed, the room is held for you and the deposit is non-refundable.</li>
          <li>
            The hotel holds the room until the &quot;arrive by&quot; time on your pass. An hour after that we ask the
            hotel whether you arrived. If it says you didn&apos;t, the hotel keeps the deposit; if you did arrive, tell
            us from your pass (once) and we&apos;ll settle it with the hotel.
          </li>
          <li>
            If the hotel turns you away, tell us from your pass or on WhatsApp: you get the deposit back at once and
            we&apos;ll show you places with rooms free.
          </li>
          <li>
            The full rules are in the <a href="/refund-policy">refund policy</a>.
          </li>
        </ul>
      </section>

      <section>
        <h2>At the hotel</h2>
        <ul>
          <li>Show your 4-digit code at the desk. Every guest needs a valid photo ID.</li>
          <li>The hotel&apos;s own rules apply, and it can refuse guests without a valid ID.</li>
          <li>Leave by the time on your pass.</li>
        </ul>
      </section>

      <section>
        <h2>If something goes wrong</h2>
        <p>
          The hotel is responsible for the room and its service. If there&apos;s a problem with a booking or a payment,
          write to us at <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a> with your booking code and we&apos;ll
          help sort it out with the hotel.
        </p>
      </section>

      <section>
        <h2>Changes</h2>
        <p>
          We may update these terms. The version on this page when you book is the one that applies to that booking.
        </p>
      </section>
    </LegalPage>
  );
}
