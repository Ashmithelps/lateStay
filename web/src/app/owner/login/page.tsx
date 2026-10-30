import type { Metadata } from "next";
import { OwnerLogin } from "./OwnerLogin";

export const metadata: Metadata = { title: "Sign in · LateStay for hotels", robots: { index: false, follow: false } };

export default async function OwnerLoginPage({ searchParams }: { searchParams: Promise<{ t?: string | string[] }> }) {
  const { t } = await searchParams;
  return <OwnerLogin token={typeof t === "string" ? t : ""} />;
}
