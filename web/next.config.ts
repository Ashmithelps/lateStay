import type { NextConfig } from "next";

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
