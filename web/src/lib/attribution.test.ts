import { describe, expect, it } from "vitest";
import { analyticsId, cleanTag, firstTouch, rememberFirstTouch } from "./attribution";
import { priceBand } from "./track";

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (k) => map.get(k) ?? null,
    key: (i) => Array.from(map.keys())[i] ?? null,
    removeItem: (k) => void map.delete(k),
    setItem: (k, v) => void map.set(k, v),
  };
}

describe("first-touch UTM", () => {
  it("keeps the first tags and never overwrites them", () => {
    const s = memoryStorage();
    rememberFirstTouch("?utm_source=Instagram&utm_medium=reel&utm_campaign=manali-night-01", s);
    rememberFirstTouch("?utm_source=youtube", s);
    expect(firstTouch(s)).toEqual({ utmSource: "instagram", utmMedium: "reel", utmCampaign: "manali-night-01" });
  });
  it("ignores visits without tags, and tags that could be personal", () => {
    const s = memoryStorage();
    rememberFirstTouch("?place=manali", s);
    expect(firstTouch(s).utmSource).toBeNull();
    expect(cleanTag("+919876543210")).toBeNull();
    expect(cleanTag("Asha Verma")).toBeNull();
    expect(cleanTag("reel-4821")).toBeNull();
    expect(cleanTag("reel-02")).toBe("reel-02");
  });
});

describe("analytics id", () => {
  it("is random, url-safe and stable for this browser", () => {
    const s = memoryStorage();
    const id = analyticsId(s);
    expect(id).toMatch(/^[A-Za-z0-9_-]{20,}$/);
    expect(analyticsId(s)).toBe(id);
    expect(analyticsId(memoryStorage())).not.toBe(id);
  });
});

describe("priceBand", () => {
  it("matches the backend's bands and has no 4-digit runs", () => {
    expect(priceBand(1300)).toBe("1k-1.5k");
    expect(priceBand(450)).toBe("0k-0.5k");
    expect(priceBand(12345)).toBe("12k-12.5k");
    expect(priceBand(4821)).not.toMatch(/\d{4}/);
  });
});
