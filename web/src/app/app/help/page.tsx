import type { Metadata } from "next";
import { appOnly } from "@/lib/native/server";
import { HelpScreen } from "./HelpScreen";

export const metadata: Metadata = { title: "Help · LateStay", robots: { index: false } };

export default async function HelpPage() {
  await appOnly();
  return <HelpScreen supportWhatsApp={process.env.SUPPORT_WHATSAPP_NUMBER || null} />;
}
