import type { MetadataRoute } from "next";
import { INDEXABLE, SITE_URL } from "@/lib/site";

/** Production: public pages only. Staging and local: nothing at all. */
export default function robots(): MetadataRoute.Robots {
  if (!INDEXABLE) return { rules: { userAgent: "*", disallow: "/" } };
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      disallow: ["/admin", "/owner", "/pass", "/book", "/haggle", "/stay", "/api", "/~offline", "/serwist"],
    },
    sitemap: `${SITE_URL}/sitemap.xml`,
  };
}
