import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { LegalFooter } from "@/components/LegalPage";
import { PageHeader } from "@/components/PageHeader";
import { Photo } from "@/components/Photo";
import { RatingBadge } from "@/components/RatingBadge";
import { cx, focusRing } from "@/components/ui";
import type { TownPage } from "@/lib/api";
import { formatPrice } from "@/lib/format";
import { fetchTown, fetchTowns } from "@/lib/towns-api";
import { TownRoomNow } from "./TownRoomNow";

// Static, rebuilt hourly. Towns that appear later are rendered on their first visit.
export const revalidate = 3600;
export const dynamicParams = true;

export async function generateStaticParams() {
  const towns = await fetchTowns();
  return (towns ?? []).map((t) => ({ town: t.slug }));
}

export async function generateMetadata({ params }: PageProps<"/towns/[town]">): Promise<Metadata> {
  const { town: slug } = await params;
  const town = await fetchTown(slug);
  if (!town) return { title: "Town not found · LateStay" };
  const title = `Late-night rooms in ${town.name} · LateStay`;
  const description = `Arriving in ${town.name} late at night? ${town.hotels.length} partner ${
    town.hotels.length === 1 ? "hotel takes" : "hotels take"
  } late arrivals through LateStay. Agree a price with the desk, pay a small deposit and show your code.`;
  return {
    title,
    description,
    alternates: { canonical: `/towns/${town.slug}` },
    openGraph: { title, description, url: `/towns/${town.slug}`, type: "website", siteName: "LateStay" },
  };
}

function priceText(range: TownPage["priceRange"]): string | null {
  if (!range) return null;
  return range.low === range.high
    ? `about ${formatPrice(range.low)}`
    : `${formatPrice(range.low)} to ${formatPrice(range.high)}`;
}

export default async function TownLandingPage({ params }: PageProps<"/towns/[town]">) {
  const { town: slug } = await params;
  const town = await fetchTown(slug);
  if (!town) notFound();
  const typical = priceText(town.priceRange);
  const count = town.hotels.length;

  return (
    <>
      <PageHeader backHref="/towns" backLabel="All towns" maxWidth="max-w-[880px]" />
      <main className="mx-auto max-w-[880px] px-4 pb-12 pt-8 sm:px-6">
        <h1 className="text-32 font-semibold text-ink">Late-night rooms in {town.name}</h1>
        <div className="mt-4 max-w-prose space-y-3 text-16 text-ink-secondary">
          <p>
            Getting into {town.name} late at night? {count} partner {count === 1 ? "hotel takes" : "hotels take"} late
            arrivals through LateStay, and each one tells us every evening how many rooms it still has.
          </p>
          <p>
            Search when you&apos;re on your way, agree a price for the rest of the night with the hotel&apos;s desk in a
            chat, pay a small deposit and show your 4-digit code when you arrive.
          </p>
        </div>
        <div className="mt-6">
          <TownRoomNow slug={town.slug} name={town.name} lat={town.lat} lng={town.lng} />
        </div>

        <section aria-labelledby="hotels" className="mt-12">
          <h2 id="hotels" className="text-22 font-semibold text-ink">
            Partner hotels
          </h2>
          {typical && town.priceRange && (
            <p className="mt-2 text-16 text-ink-secondary">
              Late at night, travellers here have usually agreed{" "}
              <span className="font-semibold text-ink tabular-nums">{typical}</span> for a room (the middle half of{" "}
              {town.priceRange.bookings} stays in the last 90 days). Tonight&apos;s price depends on the time you arrive
              and the rooms left.
            </p>
          )}
          <ul className="mt-6 grid gap-6 sm:grid-cols-2">
            {town.hotels.map((h) => {
              const range = priceText(h.priceRange);
              return (
                <li key={h.slug} className="min-w-0">
                  <Link href={`/hotels/${h.slug}`} className={cx("group flex gap-4 rounded-card", focusRing)}>
                    <Photo src={h.photo} alt="" sizes="112px" className="aspect-[20/19] w-28 shrink-0 rounded-card" />
                    <span className="min-w-0 flex-1 py-1">
                      <span className="flex items-start justify-between gap-3">
                        <span className="truncate text-16 font-semibold text-ink group-hover:underline">{h.name}</span>
                        <RatingBadge rating={h.rating} count={h.reviewCount} />
                      </span>
                      <span className="block truncate text-14 text-ink-secondary">{h.address}</span>
                      <span className="mt-1 block text-14 text-ink">
                        {range ? (
                          <>
                            Usually <span className="font-semibold tabular-nums">{range}</span> late at night
                          </>
                        ) : (
                          <span className="text-ink-secondary">Not enough recent stays to show a price</span>
                        )}
                      </span>
                    </span>
                  </Link>
                </li>
              );
            })}
          </ul>
        </section>

        <section aria-labelledby="faq" className="mt-12">
          <h2 id="faq" className="text-22 font-semibold text-ink">
            Questions
          </h2>
          <dl className="mt-4 divide-y divide-hairline border-y border-hairline">
            {FAQ.map((q) => (
              <div key={q.q} className="py-5">
                <dt className="text-16 font-semibold text-ink">{q.q}</dt>
                <dd className="mt-1 max-w-prose text-16 text-ink-secondary">{q.a}</dd>
              </div>
            ))}
          </dl>
          <p className="mt-4 text-14 text-ink-secondary">
            The full rules are in the{" "}
            <Link href="/refund-policy" className="text-ink underline underline-offset-4">
              cancellation and refund policy
            </Link>
            .
          </p>
        </section>
      </main>
      <LegalFooter />
    </>
  );
}

/** Worded from /refund-policy and the hotel pages' ID rules: change them together. */
const FAQ = [
  {
    q: "What ID do I need?",
    a: "A government photo ID for every guest, shown at check-in. Some hotels don't accept IDs with a local address; each hotel's page says whether it does.",
  },
  {
    q: "How much do I pay online?",
    a: "A deposit of ₹200, or the whole price if it's less than that. You pay the rest at the hotel's desk.",
  },
  {
    q: "Can I cancel?",
    a: "Yes, with the full deposit back, until the hotel confirms (usually within 5 minutes). If the hotel declines or doesn't confirm in 5 minutes, the deposit is refunded automatically. Once the hotel has confirmed, the room is held for you and the deposit isn't refunded.",
  },
  {
    q: "What if the hotel turns me away?",
    a: "Tap “The hotel turned me away” on your pass. We refund the deposit straight away and show you places with rooms free.",
  },
];
