import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

async function load() {
  vi.resetModules();
  const robots = (await import("@/app/robots")).default;
  const sitemap = (await import("@/app/sitemap")).default;
  return { robots, sitemap };
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("robots and sitemap", () => {
  it("keep staging out of search engines entirely", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "staging");
    const { robots, sitemap } = await load();
    expect(robots().rules).toEqual({ userAgent: "*", disallow: "/" });
    expect(await sitemap()).toEqual([]);
  });

  it("list towns and their hotels in production, never private pages", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://latestay.in/");
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        const body = url.endsWith("/api/towns")
          ? [{ slug: "old-manali", name: "Old Manali", hotels: 1 }]
          : { slug: "old-manali", name: "Old Manali", lat: 0, lng: 0, priceRange: null, hotels: [{ slug: "moss-and-pine" }] };
        return new Response(JSON.stringify(body), { status: 200 });
      }),
    );
    const { robots, sitemap } = await load();
    const rules = robots();
    expect(rules.sitemap).toBe("https://latestay.in/sitemap.xml");
    const disallowed = (rules.rules as { disallow: string[] }).disallow;
    expect(disallowed).toEqual(expect.arrayContaining(["/admin", "/owner", "/pass", "/stay", "/api"]));

    const urls = (await sitemap()).map((e) => e.url);
    expect(urls).toContain("https://latestay.in/towns/old-manali");
    expect(urls).toContain("https://latestay.in/hotels/moss-and-pine");
    expect(urls.some((u) => /\/(pass|stay|book|haggle|admin|owner)\//.test(u))).toBe(false);
  });
});
