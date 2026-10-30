import type { Metadata } from "next";
import { api } from "@/lib/api";
import { prefetch } from "@/lib/server-fetch";
import { HotelScreen } from "./HotelScreen";

export async function generateMetadata({ params }: PageProps<"/hotels/[slug]">): Promise<Metadata> {
  const { slug } = await params;
  const hotel = await prefetch(api.hotel(slug));
  if (!hotel) return { title: "LateStay" };
  const title = `${hotel.name}, ${hotel.town} · LateStay`;
  const description = `${hotel.name} on ${hotel.address} takes late-night arrivals through LateStay. Agree a price with the desk and pay a small deposit.`;
  return { title, description, alternates: { canonical: `/hotels/${hotel.slug}` }, openGraph: { title, description } };
}

export default async function HotelPage({ params, searchParams }: PageProps<"/hotels/[slug]">) {
  const { slug } = await params;
  const stay = (await searchParams).stay;
  const initial = await prefetch(api.hotel(slug, typeof stay === "string" && stay ? stay : undefined));
  return <HotelScreen initial={initial} />;
}
