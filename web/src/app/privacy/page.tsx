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
            <strong>Checking your number:</strong> before you pay we send a 6-digit code to your WhatsApp. We keep
            only a scrambled (hashed) form of the number and the code, delete it within two days, and remember on your
            phone for 30 days that the number was checked (a cookie that contains no number).
          </li>
          <li>
            <strong>WhatsApp updates, only if you ask:</strong> at the phone step there is an unticked box, &quot;Send my
            booking pass and updates on WhatsApp&quot;. If you tick it, we record that you did and when. We then send
            that number at most these messages about that one booking: your booking pass (hotel, address, arrive-by
            time, amount to pay at the desk, desk code, a link to the pass and to our support) when the hotel confirms,
            and one short line with the pass link if the booking is declined, isn&apos;t confirmed in time, is cancelled
            after you paid, is rescued by our team, or when your refund goes through; and, the morning after a stay that
            happened, one message asking how it was, with a link to rate it on your pass. Nothing else, and no
            marketing. If you don&apos;t tick it, the only WhatsApp message you get from us is the code.
          </li>
          <li>
            <strong>Your rating, if you leave one:</strong> after your stay you can rate it from your pass: 1 to 5 stars,
            a few tags (such as &quot;Clean&quot; or &quot;Warm&quot;) and, if you like, up to 300 characters of text.
            It&apos;s shown on the hotel&apos;s page with the month of your stay and never your name; the hotel sees it
            on its own page too. Our team can hide text that is abusive.
          </li>
          <li>
            <strong>A shared stay, if you make one:</strong> &quot;Share with family&quot; on your pass makes a link that
            shows the hotel&apos;s name, address and map, your arrival time and whether you&apos;ve checked in, never
            your code, phone number or prices. Anyone with the link can open it until a day after your stay ends, or
            until you turn it off.
          </li>
          <li>
            <strong>Your payment:</strong> Razorpay handles the card or UPI payment. We receive only a payment
            reference and whether it succeeded or was refunded, never your card or UPI details.
          </li>
        </ul>
        <p>We don&apos;t ask for your email, we don&apos;t have accounts or passwords, and we don&apos;t use advertising or tracking cookies. The only cookie is the one that remembers your number was checked.</p>
      </section>

      <section>
        <h2>Why we store it</h2>
        <ul>
          <li>To show you hotels with rooms near you and let you agree a price.</li>
          <li>To hold the room, tell the hotel you&apos;re coming and give you your booking pass.</li>
          <li>
            To send you the WhatsApp updates above, if you ticked the box. Otherwise the booking pass page is where
            changes show up.
          </li>
          <li>To make sure the number belongs to you before you pay, so bookings aren&apos;t made in someone else&apos;s name.</li>
          <li>To refund you when a booking falls through, and to sort out problems if you contact us.</li>
          <li>To keep the service working and safe: for example, limiting how fast messages can be sent.</li>
        </ul>
      </section>

      <section>
        <h2>Who else sees it</h2>
        <ul>
          <li>
            <strong>The hotel</strong> gets your name, the number of guests, your arrival time, the amounts and your
            desk code. It does not get your phone number from us.
          </li>
          <li>
            <strong>Razorpay</strong> processes your deposit and any refund.
          </li>
          <li>
            <strong>Meta (WhatsApp)</strong> delivers the verification code to your WhatsApp number, the booking
            updates if you asked for them, and our messages to hotel owners, which include the booking details above
            but not your number.
          </li>
          <li>
            <strong>PostHog</strong> receives the analytics counts described below, from our servers, without your IP
            address.
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
            us with the payment and our copies of any WhatsApp updates we sent you.
          </li>
          <li>
            The rest of the booking (name, dates, amounts) stays in our records for our accounts and in case of a
            dispute.
          </li>
          <li>Searches and chat messages are kept so we can run and improve the service, unless you ask us to delete them.</li>
        </ul>
      </section>

      <section>
        <h2>Analytics</h2>
        <p>
          To see which steps work and which don&apos;t, we count steps such as &quot;searched&quot;, &quot;opened a
          hotel&quot;, &quot;agreed a price&quot; and &quot;paid&quot;. Each count carries only the town, the hotel, the
          number of guests or a price range, never your name, phone number, booking code or verification code. It is
          tied to a random id kept in your browser, not to you.
        </p>
        <p>
          If you arrived from a link with tags such as <code>utm_source=instagram</code>, we keep the first such tags
          with your search and booking so we can tell which posts bring bookings.
        </p>
        <p>
          The counts are stored by us and sent from our servers to PostHog (in the EU), which we use to read them. Your
          browser never connects to PostHog and it doesn&apos;t receive your IP address. We don&apos;t use advertising
          or cross-site tracking.
        </p>
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
