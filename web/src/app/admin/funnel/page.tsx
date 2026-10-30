import type { Metadata } from "next";
import { Suspense } from "react";
import { AdminScreen } from "../AdminScreen";

export const metadata: Metadata = { title: "Funnel · LateStay admin", robots: { index: false } };

export default function AdminFunnelPage() {
  return (
    <Suspense fallback={null}>
      <AdminScreen route="funnel" />
    </Suspense>
  );
}
