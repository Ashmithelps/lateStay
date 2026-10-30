import type { NextConfig } from "next";

/** Where uploaded hotel photos are served from (R2's public URL; same as the backend's R2_PUBLIC_BASE_URL). */
function photoPattern(): URL | null {
  try {
    return process.env.NEXT_PUBLIC_PHOTO_BASE_URL ? new URL(process.env.NEXT_PUBLIC_PHOTO_BASE_URL) : null;
  } catch {
    return null;
  }
}
const photoBase = photoPattern();

const nextConfig: NextConfig = {
  // No cacheComponents: every page here is per-request (search, hotel for a stay, chat, pass), so a static shell
  // would be empty, and content streamed into a Suspense boundary is revealed ~300 ms late, after the LCP photo
  // has loaded. Pages fetch their first data on the server and render it in the first HTML instead.
  poweredByHeader: false,
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "X-Frame-Options", value: "DENY" },
          // "Use my location" in search needs geolocation; nothing else does.
          { key: "Permissions-Policy", value: "geolocation=(self), camera=(), microphone=(), payment=(self)" },
        ],
      },
      // The service worker must always be revalidated so updates reach installed apps.
      { source: "/serwist/:path*", headers: [{ key: "Cache-Control", value: "no-cache" }] },
    ];
  },
  images: {
    formats: ["image/avif", "image/webp"],
    // Cards are at most ~400 px wide at 2x; the hotel page's large photo is half of a 1280 px layout.
    deviceSizes: [390, 640, 750, 828, 1080, 1280, 1920],
    // Uploaded photos use their stored 400/800/1600 px WebP files (lib/photo-loader.ts); this allows them anywhere
    // next/image is used without that loader.
    remotePatterns: photoBase
      ? [
          {
            protocol: photoBase.protocol.replace(":", "") as "http" | "https",
            hostname: photoBase.hostname,
            port: photoBase.port,
            pathname: `${photoBase.pathname.replace(/\/$/, "")}/**`,
          },
        ]
      : [],
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
