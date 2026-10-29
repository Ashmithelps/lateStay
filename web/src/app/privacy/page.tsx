import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { LEGAL_UPDATED, SUPPORT_EMAIL } from "@/lib/legal";

export const metadata: Metadata = { title: "Privacy · LateStay" };

export default function PrivacyPage() {
  return (
    <LegalPage title="Privacy" updated={LEGAL_UPDATED}>
      <section>
        <p>
          LateStay helps you find a room for the rest of the night and book it. This page explains, in plain words,
          what we keep about you, why, who else sees it and when we delete it.
        </p>
      </section>

      <section>
        <h2>What we store</h2>
        <ul>
          <li>
            <strong>Your search:</strong> the town or the location you searched from, your arrival and leaving times
            and the number of guests.
          </li>
          <li>
            <strong>Your chat with the front desk:</strong> the messages you send while haggling, and the replies.
          </li>
          <li>
            <strong>Your booking:</strong> your name, your mobile number, the number of guests, your times, the price
            you agreed, what you paid online and the 4-digit desk code.
          </li>
          <li>
            <strong>Your payment:</strong> Razorpay handles the card or UPI payment. We receive only a payment
            reference and whether it succeeded or was refunded, never your card or UPI details.
          </li>
        </ul>
        <p>We don&apos;t ask for your email, we don&apos;t have accounts or passwords, and we don&apos;t use advertising or tracking cookies.</p>
      </section>

      <section>
        <h2>Why we store it</h2>
        <ul>
          <li>To show you hotels with rooms near you and let you agree a price.</li>
          <li>To hold the room, tell the hotel you&apos;re coming and give you your booking pass.</li>
          <li>To contact you about this booking if something changes, for example if the hotel can&apos;t take it.</li>
          <li>To refund you when a booking falls through, and to sort out problems if you contact us.</li>
          <li>To keep the service working and safe: for example, limiting how fast messages can be sent.</li>
        </ul>
      </section>

      <section>
        <h2>Who else sees it</h2>
        <ul>
          <li>
            <strong>The hotel</strong> gets the number of guests, your arrival time, the amounts and your desk code.
            It does not get your phone number from us.
          </li>
          <li>
            <strong>Razorpay</strong> processes your deposit and any refund.
          </li>
          <li>
            <strong>Meta (WhatsApp)</strong> carries our messages to hotel owners, which include the booking details
            above but not your name or number.
          </li>
          <li>
            <strong>Our service providers</strong> run our servers and database (in Singapore) and record app errors
            so we can fix them. Phone numbers are removed from error reports and logs.
          </li>
        </ul>
        <p>We don&apos;t sell your information to anyone.</p>
      </section>

      <section>
        <h2>How long we keep it</h2>
        <ul>
          <li>
            <strong>Your phone number is deleted 90 days after your stay</strong>, including the copy Razorpay sent
            us with the payment.
          </li>
          <li>
            The rest of the booking (name, dates, amounts) stays in our records for our accounts and in case of a
            dispute.
          </li>
          <li>Searches and chat messages are kept so we can run and improve the service, unless you ask us to delete them.</li>
        </ul>
      </section>

      <section>
        <h2>On your phone</h2>
        <p>
          The app keeps a few things in your browser so it works quickly and offline: your last search, the hotels you
          saved, the ids of your bookings, and copies of pages you&apos;ve opened (such as your booking pass). Clearing
          your browser&apos;s site data removes them.
        </p>
      </section>

      <section>
        <h2>Your choices</h2>
        <p>
          You can ask us for a copy of what we hold about you, ask us to correct it, or ask us to delete it, by
          writing to <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>. Tell us the mobile number you booked
          with so we can find it. If we can&apos;t delete something yet (for example a booking we must keep for our
          accounts), we&apos;ll tell you why.
        </p>
      </section>
    </LegalPage>
  );
}
