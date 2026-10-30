import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { BUSINESS_ADDRESS, LEGAL_UPDATED, SUPPORT_EMAIL, supportWhatsApp, waLink } from "@/lib/legal";

export const metadata: Metadata = { title: "Contact · LateStay" };

export default function ContactPage() {
  const whatsapp = supportWhatsApp();
  return (
    <LegalPage title="Contact" updated={LEGAL_UPDATED}>
      <section>
        <p>
          For help with a booking, tell us your 4-digit booking code. If you&apos;re at the hotel and something&apos;s
          wrong, WhatsApp is fastest; we answer through the night.
        </p>
      </section>
      <section>
        <h2>WhatsApp</h2>
        {whatsapp ? (
          <p>
            <a href={waLink(whatsapp, "Hi, I need help with my LateStay booking.")} target="_blank" rel="noreferrer">
              {whatsapp}
            </a>
          </p>
        ) : (
          <p>Our support number will be listed here.</p>
        )}
      </section>
      <section>
        <h2>Email</h2>
        <p>
          <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>
        </p>
      </section>
      <section>
        <h2>Address</h2>
        <p className="whitespace-pre-line">{BUSINESS_ADDRESS || "Registered business address to be added."}</p>
      </section>
    </LegalPage>
  );
}
