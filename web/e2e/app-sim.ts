import type { BrowserContext, Page } from "@playwright/test";

/**
 * Runs the web app the way the iOS/Android app's WebView does, in a normal browser: the app's user agent, and
 * Capacitor's bridge replaced by in-page stand-ins (window.CapacitorCustomPlatform makes isNativePlatform() true;
 * Capacitor.PluginHeaders + nativePromise route every plugin call here). Preferences live in localStorage; the push
 * permission and the log of native calls are in window.__nativeSim. Test-only: nothing of this ships.
 */
export const APP_USER_AGENT_SUFFIX = " LateStayApp/1.0.0";

export type SimOptions = { platform?: "ios" | "android"; push?: "prompt" | "granted" | "denied" };

function bridge({ platform, push }: Required<SimOptions>) {
  const w = window as unknown as Record<string, unknown>;
  const calls: { plugin: string; method: string; options: unknown }[] = [];
  const listeners: Record<string, ((data: unknown) => void)[]> = {};
  let nextId = 1;
  const state = { push, calls, listeners, token: "sim-fcm-token-0123456789abcdef" };
  w.__nativeSim = state;
  const fire = (plugin: string, event: string, data: unknown) =>
    (listeners[`${plugin}.${event}`] ?? []).forEach((cb) => cb(data));
  (w.__nativeSim as Record<string, unknown>).fire = fire;

  const impl: Record<string, Record<string, (o: Record<string, string>) => unknown>> = {
    Preferences: {
      get: (o) => ({ value: localStorage.getItem(`sim.pref.${o.key}`) }),
      set: (o) => localStorage.setItem(`sim.pref.${o.key}`, o.value),
      remove: (o) => localStorage.removeItem(`sim.pref.${o.key}`),
    },
    PushNotifications: {
      checkPermissions: () => ({ receive: state.push }),
      requestPermissions: () => ({ receive: (state.push = "granted") }),
      register: () => setTimeout(() => fire("PushNotifications", "registration", { value: state.token }), 50),
      createChannel: () => undefined,
    },
    Geolocation: {
      checkPermissions: () => ({ location: "granted", coarseLocation: "granted" }),
      requestPermissions: () => ({ location: "granted", coarseLocation: "granted" }),
      getCurrentPosition: () => ({ coords: { latitude: 32.2432, longitude: 77.1892, accuracy: 10 }, timestamp: Date.now() }),
    },
    App: { exitApp: () => undefined, getInfo: () => ({ version: "1.0.0" }) },
    StatusBar: { setStyle: () => undefined },
    SplashScreen: { hide: () => undefined },
    Keyboard: {},
    Haptics: { impact: () => undefined, notification: () => undefined },
    Share: { share: () => ({}) },
    Browser: { open: () => undefined },
  };

  w.CapacitorCustomPlatform = { name: platform, plugins: {} };
  // As Capacitor's native-bridge.js does before the page's scripts run.
  w.Capacitor = {
    isNativePlatform: () => true,
    getPlatform: () => platform,
    PluginHeaders: Object.entries(impl).map(([name, methods]) => ({
      name,
      methods: [...Object.keys(methods), "addListener", "removeListener"].map((m) => ({
        name: m,
        rtype: m === "addListener" ? "callback" : "promise",
      })),
    })),
    nativePromise: async (plugin: string, method: string, options: Record<string, string>) => {
      calls.push({ plugin, method, options });
      const fn = impl[plugin]?.[method];
      if (!fn) throw new Error(`${plugin}.${method} not simulated`);
      return fn(options ?? {});
    },
    nativeCallback: (plugin: string, method: string, options: { eventName: string }, callback: (d: unknown) => void) => {
      calls.push({ plugin, method, options });
      if (method === "addListener") (listeners[`${plugin}.${options.eventName}`] ??= []).push(callback);
      return String(nextId++);
    },
  };
}

/** A browser context that looks like the app: user agent, viewport and the bridge stand-ins on every page. */
export async function simulateApp(context: BrowserContext, options: SimOptions = {}) {
  await context.addInitScript(bridge, { platform: options.platform ?? "ios", push: options.push ?? "prompt" });
}

/** The native calls made so far, e.g. to check the device registered for a booking. */
export async function nativeCalls(page: Page): Promise<{ plugin: string; method: string; options: unknown }[]> {
  return page.evaluate(() => (window as unknown as { __nativeSim: { calls: never[] } }).__nativeSim.calls);
}

/** Fires a native event, e.g. the traveller tapping a notification (once the page is listening for it). */
export async function fireNative(page: Page, plugin: string, event: string, data: unknown) {
  await page.waitForFunction(
    (key) => ((window as unknown as { __nativeSim: { listeners: Record<string, unknown[]> } }).__nativeSim.listeners[key] ?? []).length > 0,
    `${plugin}.${event}`,
  );
  await page.evaluate(
    ([p, e, d]) => (window as unknown as { __nativeSim: { fire: (a: string, b: string, c: unknown) => void } }).__nativeSim.fire(p as string, e as string, d),
    [plugin, event, data] as const,
  );
}
