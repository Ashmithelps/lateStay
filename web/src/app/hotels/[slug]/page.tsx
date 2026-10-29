import { api } from "@/lib/api";
import { prefetch } from "@/lib/server-fetch";
import { HotelScreen } from "./HotelScreen";

export default async function HotelPage({ params, searchParams }: PageProps<"/hotels/[slug]">) {
  const { slug } = await params;
  const stay = (await searchParams).stay;
  const initial = await prefetch(api.hotel(slug, typeof stay === "string" && stay ? stay : undefined));
  return <HotelScreen initial={initial} />;
}
