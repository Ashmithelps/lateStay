import type { ReactNode } from "react";
import { PageHeader } from "./PageHeader";

/** Plain-language legal pages: one readable column, headings and short lists. */
export function LegalPage({ title, updated, children }: { title: string; updated: string; children: ReactNode }) {
  return (
    <>
      <PageHeader backHref="/" backLabel="Back to LateStay" maxWidth="max-w-[720px]" />
      <main className="mx-auto max-w-[720px] px-4 pb-16 pt-8 sm:px-6">
        <h1 className="text-32 font-semibold text-ink [text-wrap:balance]">{title}</h1>
        <p className="mt-2 text-14 text-ink-secondary">Last updated {updated}</p>
        <div className="mt-8 space-y-8 text-16 text-ink [&_h2]:text-22 [&_h2]:font-semibold [&_li]:mt-2 [&_p]:mt-3 [&_ul]:mt-3 [&_ul]:list-disc [&_ul]:pl-5 [&_a]:underline [&_a]:underline-offset-4">
          {children}
        </div>
      </main>
    </>
  );
}

export function LegalFooter() {
  return (
    <footer className="mx-auto flex max-w-[1440px] flex-wrap gap-x-6 gap-y-2 px-4 pb-8 pt-6 text-14 text-ink-secondary sm:px-6 lg:px-10">
      <span>LateStay</span>
      <a href="/privacy" className="underline-offset-4 hover:underline">
        Privacy
      </a>
      <a href="/terms" className="underline-offset-4 hover:underline">
        Terms
      </a>
    </footer>
  );
}
