import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { createSerwistRoute } from "@serwist/turbopack";

// The offline page's revision: a hash of its source, so a changed page replaces the precached copy.
const revision = createHash("sha256")
  .update(readFileSync("src/app/~offline/page.tsx"))
  .digest("hex")
  .slice(0, 16);

/**
 * Precache the traveller app, not everything: Leaflet (fetched and cached when "Show map" is first tapped) and
 * the admin panel and the hotel owner's page stay out, so a first visit on mobile data doesn't download them in the
 * background.
 */
function travellerChunk(url: string): boolean {
  // Transforms run before Serwist rewrites ".next/static/..." into "/_next/static/...".
  const file = url.startsWith(".next/") ? url : url.startsWith("/_next/") ? `.next/${url.slice("/_next/".length)}` : null;
  if (!file || !file.startsWith(".next/static/chunks/") || !file.endsWith(".js")) return true;
  try {
    const code = readFileSync(file, "utf-8");
    return !code.includes("leaflet-container") && !code.includes("Admin sign-in") && !code.includes("Sign in from WhatsApp");
  } catch {
    return true;
  }
}

export const { dynamic, dynamicParams, revalidate, generateStaticParams, GET } = createSerwistRoute({
  additionalPrecacheEntries: [{ url: "/~offline", revision }],
  swSrc: "src/app/sw.ts",
  useNativeEsbuild: true,
  manifestTransforms: [
    async (entries) => ({ manifest: entries.filter((entry) => travellerChunk(entry.url)), warnings: [] }),
  ],
  esbuildOptions: {
    define: { __API_ORIGIN__: JSON.stringify(process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080") },
  },
});
