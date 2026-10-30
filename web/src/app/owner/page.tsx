import type { Metadata } from "next";
import { OwnerScreen } from "./OwnerScreen";

export const metadata: Metadata = { title: "Your hotel · LateStay", robots: { index: false, follow: false } };

/** Signed in by cookie on the API's domain, so the first data is fetched in the browser, not here. */
export default function OwnerPage() {
  return <OwnerScreen />;
}
