import type { MetadataRoute } from "next";
import { INDEXABLE, SITE_URL } from "@/lib/site";
import { fetchTown, fetchTowns } from "@/lib/towns-api";

export const revalidate = 3600;

/** Public pages only: home, towns, their partner hotels and the legal pages. Never passes, chats or shared stays. */
export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  if (!INDEXABLE) return [];
  const towns = (await fetchTowns()) ?? [];
  const pages = await Promise.all(towns.map((t) => fetchTown(t.slug)));
  const hotels = [...new Set(pages.flatMap((p) => p?.hotels.map((h) => h.slug) ?? []))];
  return [
    { url: `${SITE_URL}/`, changeFrequency: "daily", priority: 1 },
    { url: `${SITE_URL}/towns`, changeFrequency: "weekly", priority: 0.8 },
    ...towns.map((t) => ({ url: `${SITE_URL}/towns/${t.slug}`, changeFrequency: "daily" as const, priority: 0.8 })),
    ...hotels.map((slug) => ({ url: `${SITE_URL}/hotels/${slug}`, changeFrequency: "weekly" as const, priority: 0.6 })),
    ...["privacy", "terms", "refund-policy", "contact"].map((p) => ({
      url: `${SITE_URL}/${p}`,
      changeFrequency: "monthly" as const,
      priority: 0.3,
    })),
  ];
}
