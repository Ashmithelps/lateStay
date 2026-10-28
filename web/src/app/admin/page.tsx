import type { Metadata } from "next";
import { Suspense } from "react";
import { AdminScreen } from "./AdminScreen";

export const metadata: Metadata = { title: "LateStay admin", robots: { index: false } };

export default function AdminPage() {
  return (
    <Suspense fallback={null}>
      <AdminScreen />
    </Suspense>
  );
}
