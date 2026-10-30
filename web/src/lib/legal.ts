/** Contact details shown on the privacy and terms pages. Set NEXT_PUBLIC_SUPPORT_EMAIL before launch. */
export const SUPPORT_EMAIL = process.env.NEXT_PUBLIC_SUPPORT_EMAIL || "support@latestay.example";
export const LEGAL_UPDATED = "29 October 2026";

/** Support on WhatsApp (server-side env SUPPORT_WHATSAPP_NUMBER, with country code). */
export function supportWhatsApp(): string | null {
  return process.env.SUPPORT_WHATSAPP_NUMBER || null;
}

/** "+91 98765 43210" -> "https://wa.me/919876543210" */
export function waLink(number: string, text?: string): string {
  return `https://wa.me/${number.replace(/\D/g, "")}${text ? `?text=${encodeURIComponent(text)}` : ""}`;
}

/** The registered business address for /contact (Razorpay asks for one). Set before going live. */
export const BUSINESS_ADDRESS = process.env.NEXT_PUBLIC_BUSINESS_ADDRESS || "";
