import type { Metadata } from "next";
import { OfflineScreen } from "./OfflineScreen";

export const metadata: Metadata = { title: "Offline · LateStay" };

/** Served by the service worker when a page isn't cached and there's no connection. */
export default function OfflinePage() {
  return <OfflineScreen />;
}
