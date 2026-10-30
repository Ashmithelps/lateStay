import { describe, expect, it } from "vitest";
import { backAction } from "./back";
import { linkTarget, mapsUrl } from "./links";
import { NATIVE_BOOT_SCRIPT, isAppUserAgent } from "./platform";
import { deepLinkPath, notificationPath } from "./push-routing";
import { asBookingView, parsePasses, prune, upsertPass, type SavedPass } from "./saved-passes";
import { tabBarVisible, tabFor } from "./tabs";

const ID = "0b6f3c5e-8c1a-4a57-9d55-1d0f6a2c3b4e";

describe("tab bar", () => {
  it("never shows on the website", () => {
    for (const path of ["/", "/app/bookings", "/app/help", `/pass/${ID}`]) expect(tabBarVisible(false, path)).toBe(false);
  });
  it("shows in the app on the tabs' pages only", () => {
    expect(tabFor("/")).toBe("tonight");
    expect(tabFor("/app/bookings")).toBe("bookings");
    expect(tabFor(`/pass/${ID}`)).toBe("bookings");
    expect(tabFor("/app/help")).toBe("help");
    for (const path of ["/hotels/deodar-nest", `/haggle/${ID}`, `/book/${ID}`, "/stay/abc", "/admin", "/privacy"]) {
      expect(tabBarVisible(true, path)).toBe(false);
    }
    expect(tabBarVisible(true, "/")).toBe(true);
  });
});

describe("android back button", () => {
  it("exits only from Tonight", () => {
    expect(backAction("/", true)).toBe("exit");
    expect(backAction("/app/help", true)).toBe("history-back");
    expect(backAction(`/pass/${ID}`, false)).toBe("go-tonight");
  });
});

describe("links", () => {
  const origin = "https://latestay.in";
  it("keeps our pages in the app and opens policies in the in-app browser", () => {
    expect(linkTarget("/hotels/x", origin)).toEqual({ kind: "in-app" });
    expect(linkTarget("/privacy", origin)).toEqual({ kind: "in-app-browser", url: "https://latestay.in/privacy" });
    expect(linkTarget("https://latestay.in/refund-policy", origin).kind).toBe("in-app-browser");
  });
  it("sends phones, maps, WhatsApp and other sites outside the app", () => {
    expect(linkTarget("tel:+919000000001", origin)).toEqual({ kind: "system", url: "tel:+919000000001" });
    expect(linkTarget("https://wa.me/919000022222?text=hi", origin).kind).toBe("system");
    expect(linkTarget("https://www.google.com/maps/dir/?api=1", origin).kind).toBe("system");
    expect(linkTarget("geo:1,2", origin).kind).toBe("system");
  });
  it("opens directions in the platform's maps app", () => {
    expect(mapsUrl("ios", 32.2, 77.1, "Deodar Nest")).toBe("maps://?daddr=32.2,77.1&q=Deodar%20Nest");
    expect(mapsUrl("android", 32.2, 77.1, "Deodar Nest")).toBe("geo:32.2,77.1?q=32.2,77.1(Deodar%20Nest)");
  });
});

describe("notifications and deep links", () => {
  it("open the booking's pass", () => {
    expect(notificationPath({ path: `/pass/${ID}`, bookingId: ID })).toBe(`/pass/${ID}`);
    expect(notificationPath({ path: `/pass/${ID}/review` })).toBe(`/pass/${ID}/review`);
    expect(notificationPath({ bookingId: ID })).toBe(`/pass/${ID}`);
    expect(notificationPath({ path: "https://evil.example/x" })).toBe("/");
    expect(notificationPath(undefined)).toBe("/");
  });
  it("only accept our hosts and the pass and stay paths", () => {
    const hosts = ["latestay.in", "staging.latestay.in"];
    expect(deepLinkPath(`https://latestay.in/pass/${ID}`, hosts)).toBe(`/pass/${ID}`);
    expect(deepLinkPath("https://staging.latestay.in/stay/RKyKMXr0yaTAaBLsnHgJqqAH", hosts)).toBe(
      "/stay/RKyKMXr0yaTAaBLsnHgJqqAH",
    );
    expect(deepLinkPath("https://latestay.in/admin", hosts)).toBeNull();
    expect(deepLinkPath(`https://evil.example/pass/${ID}`, hosts)).toBeNull();
  });
});

describe("saved passes", () => {
  const pass = (id: string, leaveAt: string, savedAt: number): SavedPass => ({
    id,
    status: "HOTEL_CONFIRMED",
    hotel: { name: "Deodar Nest", address: "Lane 2", town: "Manali", lat: 1, lng: 2, phone: "+91 90000 00001" },
    sessionId: "s",
    roomType: "Deluxe",
    guestName: "Asha Verma",
    guests: 2,
    arriveAt: "2026-11-11T23:30:00",
    arriveBy: "2026-11-11T23:30:00",
    leaveAt,
    code: "4821",
    agreedPrice: 1100,
    depositAmount: 200,
    payAtDesk: 900,
    savedAt,
  });
  const now = new Date("2026-11-12T12:00:00+05:30").getTime();

  it("keeps the newest first, replacing a pass saved again", () => {
    const a = pass("a", "2026-11-12T09:00:00", 1);
    const b = pass("b", "2026-11-12T09:00:00", 2);
    const list = upsertPass(upsertPass([], a, now), b, now);
    expect(list.map((p) => p.id)).toEqual(["b", "a"]);
    expect(upsertPass(list, { ...a, savedAt: 3, code: "1111" }, now).map((p) => [p.id, p.code])).toEqual([
      ["a", "1111"],
      ["b", "4821"],
    ]);
  });
  it("drops passes 30 days after their stay", () => {
    const old = pass("old", "2026-10-12T09:00:00", 1);
    const recent = pass("recent", "2026-10-14T09:00:00", 2);
    expect(prune([old, recent], now).map((p) => p.id)).toEqual(["recent"]);
  });
  it("shows offline with the code and amounts but no actions", () => {
    const view = asBookingView(pass("a", "2026-11-12T09:00:00", 1));
    expect(view.code).toBe("4821");
    expect(view.payAtDesk).toBe(900);
    expect(view.hotel.name).toBe("Deodar Nest");
    expect(view.actions).toEqual({ cancel: false, turnedAway: false, didArrive: false });
  });
  it("survives bad storage", () => {
    expect(parsePasses(null)).toEqual([]);
    expect(parsePasses("{not json")).toEqual([]);
    expect(parsePasses('[{"id":"x"},{"nope":1}]')).toHaveLength(1);
  });
});

describe("boot script", () => {
  it("only marks the app", () => {
    expect(isAppUserAgent("Mozilla/5.0 ... LateStayApp/1.0.0")).toBe(true);
    expect(isAppUserAgent("Mozilla/5.0 (iPhone) Safari/604.1")).toBe(false);
    expect(NATIVE_BOOT_SCRIPT).toContain('classList.add("native")');
    expect(NATIVE_BOOT_SCRIPT).toContain("viewport-fit=cover");
  });
});
