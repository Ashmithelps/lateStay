"use client";

import { isNative, nativePlatform } from "./platform";
import { STORAGE_KEY, parsePasses, toSavedPass, upsertPass, type SavedPass } from "./saved-passes";
import type { BookingView } from "@/lib/api";

/*
 * Every Capacitor plugin is imported lazily and only in the app, so the website never downloads them. Each helper
 * has the website's behaviour as its fallback.
 */

export async function haptic(kind: "light" | "success"): Promise<void> {
  if (!isNative()) return;
  const { Haptics, ImpactStyle, NotificationType } = await import("@capacitor/haptics");
  try {
    if (kind === "light") await Haptics.impact({ style: ImpactStyle.Light });
    else await Haptics.notification({ type: NotificationType.Success });
  } catch {
    // no haptics on this device
  }
}

/** The device's position: the app's location permission ("To find rooms free tonight near you."), else the browser's. */
export async function currentPosition(): Promise<{ lat: number; lng: number }> {
  if (isNative()) {
    const { Geolocation } = await import("@capacitor/geolocation");
    const permission = await Geolocation.checkPermissions().catch(() => null);
    if (permission?.location !== "granted") {
      const asked = await Geolocation.requestPermissions({ permissions: ["location"] });
      if (asked.location !== "granted") throw new Error("location denied");
    }
    const pos = await Geolocation.getCurrentPosition({ timeout: 8000, maximumAge: 60_000 });
    return { lat: pos.coords.latitude, lng: pos.coords.longitude };
  }
  if (typeof navigator === "undefined" || !("geolocation" in navigator)) throw new Error("no geolocation");
  return new Promise((resolve, reject) =>
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      reject,
      { timeout: 8000, maximumAge: 60_000 },
    ),
  );
}

/** The native share sheet in the app. Returns false on the website (the caller keeps its own behaviour). */
export async function nativeShare(title: string, text: string, url: string): Promise<boolean> {
  if (!isNative()) return false;
  const { Share } = await import("@capacitor/share");
  try {
    await Share.share({ title, text, url, dialogTitle: "Share with family" });
  } catch {
    // dismissed
  }
  return true;
}

/** Policy pages in the in-app browser (SFSafariViewController / Custom Tabs). */
export async function openInAppBrowser(url: string): Promise<void> {
  const { Browser } = await import("@capacitor/browser");
  await Browser.open({ url, presentationStyle: "popover", toolbarColor: "#ffffff" });
}

/** Hands a URL to the system: the dialler, the maps app, WhatsApp, the browser. */
export function openOutside(url: string): void {
  window.location.href = url;
}

export { nativePlatform };

// Saved passes (Preferences): the app's offline copy of every pass the traveller opens.

export async function readSavedPasses(): Promise<SavedPass[]> {
  if (!isNative()) return [];
  const { Preferences } = await import("@capacitor/preferences");
  return parsePasses((await Preferences.get({ key: STORAGE_KEY })).value);
}

export async function savePass(booking: BookingView): Promise<void> {
  if (!isNative()) return;
  const { Preferences } = await import("@capacitor/preferences");
  const now = Date.now();
  const passes = upsertPass(parsePasses((await Preferences.get({ key: STORAGE_KEY })).value), toSavedPass(booking, now), now);
  await Preferences.set({ key: STORAGE_KEY, value: JSON.stringify(passes) });
}
