import type { Metadata } from "next";
import { appOnly } from "@/lib/native/server";
import { BookingsScreen } from "./BookingsScreen";

export const metadata: Metadata = { title: "Bookings · LateStay", robots: { index: false } };

export default async function BookingsPage() {
  await appOnly();
  return <BookingsScreen />;
}
