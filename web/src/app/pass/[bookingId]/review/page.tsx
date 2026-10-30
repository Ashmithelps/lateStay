import type { Metadata } from "next";
import { api } from "@/lib/api";
import { prefetch } from "@/lib/server-fetch";
import { ReviewScreen } from "./ReviewScreen";

export const metadata: Metadata = { title: "Rate your stay · LateStay", robots: { index: false } };

export default async function ReviewPage({ params }: PageProps<"/pass/[bookingId]/review">) {
  const { bookingId } = await params;
  return <ReviewScreen initial={await prefetch(api.reviewState(bookingId))} />;
}
