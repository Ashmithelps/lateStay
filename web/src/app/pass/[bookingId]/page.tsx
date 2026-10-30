import { api } from "@/lib/api";
import { prefetch } from "@/lib/server-fetch";
import { PassScreen } from "./PassScreen";

export default async function PassPage({ params }: PageProps<"/pass/[bookingId]">) {
  const { bookingId } = await params;
  return (
    <PassScreen
      initial={await prefetch(api.booking(bookingId))}
      supportWhatsApp={process.env.SUPPORT_WHATSAPP_NUMBER || null}
    />
  );
}
