import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { API_URL, type SharedStay } from "@/lib/api";
import { SharedStayScreen } from "./SharedStayScreen";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "A shared stay · LateStay",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

type Loaded = { stay: SharedStay } | { gone: "EXPIRED" | "TURNED_OFF" };

/** The family's view: fetched on the server each time (never cached), so a turned-off link stops at once. */
async function load(token: string): Promise<Loaded | null> {
  const response = await fetch(`${API_URL}/api/stays/${encodeURIComponent(token)}`, { cache: "no-store" });
  if (response.status === 404) return null;
  if (response.status === 410) {
    const body = (await response.json().catch(() => ({}))) as { reason?: string };
    return { gone: body.reason === "TURNED_OFF" ? "TURNED_OFF" : "EXPIRED" };
  }
  if (!response.ok) throw new Error(`shared stay: ${response.status}`);
  return { stay: (await response.json()) as SharedStay };
}

export default async function SharedStayPage({ params }: PageProps<"/stay/[shareToken]">) {
  const { shareToken } = await params;
  const loaded = await load(shareToken);
  if (!loaded) notFound();
  return "stay" in loaded ? <SharedStayScreen token={shareToken} initial={loaded.stay} /> : <SharedStayScreen token={shareToken} gone={loaded.gone} />;
}
