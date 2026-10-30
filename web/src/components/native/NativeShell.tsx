"use client";

import { useQueryClient } from "@tanstack/react-query";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import { backAction } from "@/lib/native/back";
import { linkTarget } from "@/lib/native/links";
import { openInAppBrowser, openOutside } from "@/lib/native/plugins";
import { deepLinkPath, notificationPath } from "@/lib/native/push-routing";
import { tabBarVisible, tabFor } from "@/lib/native/tabs";
import { TabBar } from "./TabBar";
import { useNative } from "./useNative";

/** Our own hosts: deep links from these open inside the app. */
const OUR_HOSTS = (process.env.NEXT_PUBLIC_APP_HOSTS ?? "latestay.in,www.latestay.in,staging.latestay.in")
  .split(",")
  .map((h) => h.trim())
  .filter(Boolean);

/**
 * Everything the iOS and Android apps add around the web app. Renders nothing and does nothing on the website.
 */
export function NativeShell() {
  const native = useNative();
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  // The back button's listener is registered once; it reads the current page from here.
  const path = useRef(pathname);
  useEffect(() => {
    path.current = pathname;
  }, [pathname]);

  const showTabs = tabBarVisible(native, pathname);
  useEffect(() => {
    document.documentElement.classList.toggle("with-tabs", showTabs);
  }, [showTabs]);

  useEffect(() => {
    if (!native) return;
    const cleanups: (() => void)[] = [];
    let cancelled = false;

    void (async () => {
      const [{ App }, { StatusBar, Style }, { SplashScreen }, { Keyboard }, { PushNotifications }] = await Promise.all([
        import("@capacitor/app"),
        import("@capacitor/status-bar"),
        import("@capacitor/splash-screen"),
        import("@capacitor/keyboard"),
        import("@capacitor/push-notifications"),
      ]);
      if (cancelled) return;
      // Dark text on white.
      await StatusBar.setStyle({ style: Style.Light }).catch(() => {});
      await SplashScreen.hide().catch(() => {});
      await PushNotifications.createChannel?.({
        id: "bookings",
        name: "Booking updates",
        description: "When the hotel confirms, and if anything changes with your booking",
        importance: 4,
      }).catch(() => {});

      const handles = await Promise.all([
        App.addListener("backButton", ({ canGoBack }) => {
          const action = backAction(path.current, canGoBack);
          if (action === "exit") void App.exitApp();
          else if (action === "history-back") window.history.back();
          else router.replace("/");
        }),
        App.addListener("appUrlOpen", ({ url }) => {
          const target = deepLinkPath(url, OUR_HOSTS);
          if (target) router.push(target);
        }),
        Keyboard.addListener("keyboardWillShow", () => document.documentElement.classList.add("keyboard-open")),
        Keyboard.addListener("keyboardWillHide", () => document.documentElement.classList.remove("keyboard-open")),
        PushNotifications.addListener("pushNotificationActionPerformed", ({ notification }) =>
          router.push(notificationPath(notification.data as Record<string, unknown>)),
        ),
        // In the foreground: refresh that booking, so the pass moves on (and buzzes when it's confirmed).
        PushNotifications.addListener("pushNotificationReceived", (notification) => {
          const id = (notification.data as Record<string, unknown> | undefined)?.bookingId;
          if (typeof id === "string") void queryClient.invalidateQueries({ queryKey: ["booking", id] });
        }),
      ]);
      for (const h of handles) cleanups.push(() => void h.remove());
    })();

    // Nothing external opens inside the app's main view.
    function onClick(event: MouseEvent) {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey) return;
      const anchor = (event.target as Element | null)?.closest?.("a[href]") as HTMLAnchorElement | null;
      if (!anchor) return;
      const target = linkTarget(anchor.getAttribute("href")!, window.location.origin);
      if (target.kind === "in-app") return;
      event.preventDefault();
      if (target.kind === "in-app-browser") void openInAppBrowser(target.url);
      else openOutside(target.url);
    }
    document.addEventListener("click", onClick, true);
    cleanups.push(() => document.removeEventListener("click", onClick, true));

    return () => {
      cancelled = true;
      cleanups.forEach((c) => c());
    };
  }, [native, router, queryClient]);

  if (!showTabs) return null;
  return <TabBar active={tabFor(pathname)!} />;
}
