import { api } from "@/lib/api";
import { prefetch } from "@/lib/server-fetch";
import { HaggleScreen } from "./HaggleScreen";

export default async function HagglePage({ params }: PageProps<"/haggle/[sessionId]">) {
  const { sessionId } = await params;
  return <HaggleScreen initial={await prefetch(api.negotiation(sessionId))} />;
}
