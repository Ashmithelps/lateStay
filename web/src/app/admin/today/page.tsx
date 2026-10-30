import type { Metadata } from "next";
import { Suspense } from "react";
import { AdminScreen } from "../AdminScreen";

export const metadata: Metadata = { title: "Today · LateStay admin", robots: { index: false } };

export default function AdminTodayPage() {
  return (
    <Suspense fallback={null}>
      <AdminScreen route="today" />
    </Suspense>
  );
}
