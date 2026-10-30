import type { CapacitorConfig } from "@capacitor/cli";
// @ts-expect-error plain ESM settings shared with scripts/configure.mjs
import { allowNavigation, appId, serverUrl, version } from "./app.config.mjs";

/**
 * LateStay for iOS and Android: the app loads the live web app (server.url, per build: staging or production) and
 * adds native features around it (docs/MOBILE.md). When the server can't be reached it shows the bundled
 * www/offline.html.
 */
const config: CapacitorConfig = {
  appId,
  appName: "LateStay",
  webDir: "www",
  backgroundColor: "#ffffff",
  // The web app recognises the app by this (and by Capacitor's bridge).
  appendUserAgent: `LateStayApp/${version}`,
  server: {
    url: serverUrl,
    errorPath: "offline.html",
    allowNavigation,
    // Only for a local web app during development (SERVER_URL=http://...).
    cleartext: serverUrl.startsWith("http://"),
  },
  ios: {
    contentInset: "never",
    scheme: "LateStay",
  },
  android: {
    allowMixedContent: false,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 1500,
      launchAutoHide: true,
      backgroundColor: "#ffffff",
      showSpinner: false,
      androidScaleType: "CENTER",
    },
    SystemBars: {
      insetsHandling: "css",
      initialViewportFitValueHint: "cover",
      style: "LIGHT",
    },
    Keyboard: {
      resize: "native",
      resizeOnFullScreen: true,
    },
    PushNotifications: {
      presentationOptions: ["badge", "sound", "alert"],
    },
  },
};

export default config;
