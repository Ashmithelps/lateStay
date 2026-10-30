/**
 * Is this page running inside the LateStay iOS or Android app? The app's WebView has Capacitor's bridge
 * (window.Capacitor) and adds "LateStayApp/<version>" to the user agent. The website never sees either, so
 * everything app-only stays off there.
 */
type CapacitorGlobal = { isNativePlatform?: () => boolean; getPlatform?: () => string };

export const APP_USER_AGENT = "LateStayApp/";

export function isNative(): boolean {
  if (typeof window === "undefined") return false;
  const cap = (window as unknown as { Capacitor?: CapacitorGlobal }).Capacitor;
  return cap?.isNativePlatform?.() === true;
}

export function nativePlatform(): "ios" | "android" | "web" {
  if (!isNative()) return "web";
  const platform = (window as unknown as { Capacitor?: CapacitorGlobal }).Capacitor?.getPlatform?.();
  return platform === "ios" || platform === "android" ? platform : "web";
}

/** Server side: the request came from the app (its WebView's user agent). */
export function isAppUserAgent(userAgent: string | null | undefined): boolean {
  return !!userAgent && userAgent.includes(APP_USER_AGENT);
}

/**
 * Runs first in <body>, before anything paints: in the app only, marks <html> with "native" (the CSS's app-only
 * variant) and lets the page use the whole screen (viewport-fit=cover) so it can pad for the notch and home
 * indicator itself. On the website it does nothing.
 */
export const NATIVE_BOOT_SCRIPT = `(function(){try{var c=window.Capacitor;var n=(c&&c.isNativePlatform&&c.isNativePlatform())||navigator.userAgent.indexOf(${JSON.stringify(APP_USER_AGENT)})>=0;if(!n)return;var d=document.documentElement;d.classList.add("native");var m=document.querySelector('meta[name="viewport"]');if(m&&m.content.indexOf("viewport-fit")<0)m.content+=", viewport-fit=cover";}catch(e){}})();`;
