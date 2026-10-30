"use client";

import { request } from "@/lib/api";
import { isNative, nativePlatform } from "./platform";

/*
 * Push notifications in the app. Permission is asked only after the deposit is paid, behind our own card first
 * ("Get a notification when the hotel confirms."), never at launch. Once allowed, each new booking's pass registers
 * the device for that booking without asking again.
 */

const ASKED_KEY = "latestay.push.asked";

export type PushState = "unsupported" | "prompt" | "granted" | "denied";

export async function pushState(): Promise<PushState> {
  if (!isNative()) return "unsupported";
  const { PushNotifications } = await import("@capacitor/push-notifications");
  const { receive } = await PushNotifications.checkPermissions();
  if (receive === "granted") return "granted";
  if (receive === "denied") return "denied";
  return "prompt";
}

/** Whether our card was already answered on this device (asked once per install). */
export async function alreadyAsked(): Promise<boolean> {
  const { Preferences } = await import("@capacitor/preferences");
  return (await Preferences.get({ key: ASKED_KEY })).value === "1";
}

export async function markAsked(): Promise<void> {
  const { Preferences } = await import("@capacitor/preferences");
  await Preferences.set({ key: ASKED_KEY, value: "1" });
}

/** Asks the system (after our card's "Turn on"), then registers this device for the booking. */
export async function enableFor(bookingId: string): Promise<PushState> {
  const { PushNotifications } = await import("@capacitor/push-notifications");
  await markAsked();
  const { receive } = await PushNotifications.requestPermissions();
  if (receive !== "granted") return "denied";
  await registerFor(bookingId);
  return "granted";
}

/** With permission already granted: gets this device's FCM token and tells the backend which booking it follows. */
export async function registerFor(bookingId: string): Promise<void> {
  const { PushNotifications } = await import("@capacitor/push-notifications");
  const platform = nativePlatform() === "ios" ? "IOS" : "ANDROID";
  const token = await new Promise<string>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("no push token")), 15_000);
    void PushNotifications.addListener("registration", (t) => {
      clearTimeout(timer);
      resolve(t.value);
    });
    void PushNotifications.addListener("registrationError", (e) => {
      clearTimeout(timer);
      reject(new Error(e.error));
    });
    void PushNotifications.register();
  });
  await request<void>("/api/devices", { method: "POST", body: JSON.stringify({ token, platform, bookingId }) });
}
