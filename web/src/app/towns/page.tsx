import type { Metadata } from "next";
import Link from "next/link";
import { LegalFooter } from "@/components/LegalPage";
import { PageHeader } from "@/components/PageHeader";
import { focusRing, cx } from "@/components/ui";
import { fetchTowns } from "@/lib/towns-api";

export const revalidate = 3600;

export const metadata: Metadata = {
  title: "Towns · LateStay",
  description: "Hill towns where LateStay's partner hotels take late-night arrivals.",
  alternates: { canonical: "/towns" },
};

export default async function TownsPage() {
  const towns = (await fetchTowns()) ?? [];
  return (
    <>
      <PageHeader backHref="/" backLabel="Search" maxWidth="max-w-[880px]" />
      <main className="mx-auto max-w-[880px] px-4 pb-12 pt-8 sm:px-6">
        <h1 className="text-32 font-semibold text-ink">Towns</h1>
        <p className="mt-2 max-w-prose text-16 text-ink-secondary">
          Places where partner hotels take guests who arrive late at night.
        </p>
        <ul className="mt-8 divide-y divide-hairline border-y border-hairline">
          {towns.map((t) => (
            <li key={t.slug}>
              <Link
                href={`/towns/${t.slug}`}
                className={cx("flex min-h-16 items-center justify-between gap-4 py-4 hover:bg-soft", focusRing)}
              >
                <span className="text-18 font-semibold text-ink">{t.name}</span>
                <span className="text-14 text-ink-secondary">
                  {t.hotels} partner {t.hotels === 1 ? "hotel" : "hotels"}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </main>
      <LegalFooter />
    </>
  );
}
