"use client";

import { ChevronRight, FileText, Mail, MessageCircle, ReceiptText, ShieldCheck } from "lucide-react";
import type { ReactNode } from "react";
import { cx, focusRing } from "@/components/ui";
import { Wordmark } from "@/components/Wordmark";

/**
 * The Help tab. Support opens WhatsApp; the policy pages open in the in-app browser (NativeShell routes the links).
 */
export function HelpScreen({ supportWhatsApp }: { supportWhatsApp: string | null }) {
  const support = supportWhatsApp
    ? `https://wa.me/${supportWhatsApp.replace(/\D/g, "")}?text=${encodeURIComponent("Hi, I need help with LateStay.")}`
    : null;
  return (
    <>
      <header className="sticky top-0 z-[1001] border-b border-hairline bg-canvas">
        <div className="mx-auto flex min-h-16 max-w-[640px] items-center px-4">
          <Wordmark />
        </div>
      </header>
      <main className="mx-auto max-w-[640px] px-4 pb-12 pt-6">
        <h1 className="text-26 font-semibold text-ink">Help</h1>
        <ul className="mt-6 divide-y divide-hairline border-y border-hairline">
          {support && (
            <Row href={support} icon={<MessageCircle size={24} strokeWidth={1.5} aria-hidden />} title="Message support on WhatsApp">
              For a problem at the hotel or with a payment
            </Row>
          )}
          <Row href="/refund-policy" icon={<ReceiptText size={24} strokeWidth={1.5} aria-hidden />} title="Cancellation and refunds">
            When you get your deposit back
          </Row>
          <Row href="/contact" icon={<Mail size={24} strokeWidth={1.5} aria-hidden />} title="Contact">
            Email and our address
          </Row>
          <Row href="/privacy" icon={<ShieldCheck size={24} strokeWidth={1.5} aria-hidden />} title="Privacy">
            What we keep and why
          </Row>
          <Row href="/terms" icon={<FileText size={24} strokeWidth={1.5} aria-hidden />} title="Terms" />
        </ul>
      </main>
    </>
  );
}

function Row({ href, icon, title, children }: { href: string; icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <li>
      <a href={href} className={cx("flex min-h-16 items-center gap-4 py-4 text-ink hover:bg-soft", focusRing)}>
        <span className="shrink-0 text-ink">{icon}</span>
        <span className="min-w-0 flex-1">
          <span className="block text-16 font-medium">{title}</span>
          {children && <span className="block text-14 text-ink-secondary">{children}</span>}
        </span>
        <ChevronRight size={20} strokeWidth={1.5} aria-hidden className="shrink-0 text-ink-secondary" />
      </a>
    </li>
  );
}
