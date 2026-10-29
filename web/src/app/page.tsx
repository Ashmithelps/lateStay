import { api } from "@/lib/api";
import { readSearch, searchApiQuery } from "@/lib/search-params";
import { prefetch, toSearchParams } from "@/lib/server-fetch";
import { HomeScreen } from "./HomeScreen";

/** Renders the first results on the server, so the first hotel photo is in the HTML. */
export default async function Home({ searchParams }: PageProps<"/">) {
  const query = searchApiQuery(readSearch(toSearchParams(await searchParams)));
  const data = await prefetch(api.search(query));
  return <HomeScreen initial={data ? { query, data } : undefined} />;
}
