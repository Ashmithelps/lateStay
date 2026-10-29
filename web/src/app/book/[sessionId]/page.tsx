import { api } from "@/lib/api";
import { prefetch } from "@/lib/server-fetch";
import { BookScreen } from "./BookScreen";

export default async function BookPage({ params }: PageProps<"/book/[sessionId]">) {
  const { sessionId } = await params;
  return <BookScreen initial={await prefetch(api.bookingPreview(sessionId))} />;
}
