import type { Metadata, Viewport } from "next";
import { Plus_Jakarta_Sans } from "next/font/google";
import { SerwistProvider } from "@serwist/turbopack/react";
import { preconnect } from "react-dom";
import { Providers } from "@/components/Providers";
import { API_URL } from "@/lib/api";
import "./globals.css";

const plusJakartaSans = Plus_Jakarta_Sans({
  variable: "--font-plus-jakarta-sans",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  // Not preloaded: on a slow connection it would compete with the first hotel photo. Text paints in the
  // size-adjusted fallback and swaps when the font arrives, without a layout shift.
  preload: false,
});

export const metadata: Metadata = {
  title: "LateStay",
  description: "A room for the rest of the night in the hills.",
  applicationName: "LateStay",
  appleWebApp: { capable: true, title: "LateStay", statusBarStyle: "default" },
  icons: { apple: "/icons/apple-touch-icon.png" },
  formatDetection: { telephone: false },
};

export const viewport: Viewport = {
  themeColor: "#ffffff",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  // Search results, hotels and passes all come from the API: open that connection while the JS downloads.
  preconnect(API_URL, { crossOrigin: "anonymous" });
  return (
    <html lang="en" className={`${plusJakartaSans.variable} h-full antialiased`}>
      <body className="min-h-full bg-canvas font-sans text-16 text-ink">
        {/* Off in development so stale caches never get in the way while editing. No reload when the connection
            comes back: it would wipe a half-filled booking form (the offline notes update on their own). */}
        <SerwistProvider
          swUrl="/serwist/sw.js"
          disable={process.env.NODE_ENV === "development"}
          cacheOnNavigation
          reloadOnOnline={false}
        >
          <Providers>{children}</Providers>
        </SerwistProvider>
      </body>
    </html>
  );
}
