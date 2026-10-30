/** The site's own address (canonical URLs, sitemap, robots, Open Graph). */
export const SITE_URL = (process.env.NEXT_PUBLIC_SITE_URL || "http://localhost:3000").replace(/\/+$/, "");

/** Staging and local builds ask search engines to stay away entirely. */
export const INDEXABLE = process.env.NEXT_PUBLIC_APP_ENV === "production";
