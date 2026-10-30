/**
 * Android's back button: back through history, and out of the app only from the Tonight tab. Anywhere else with
 * nothing to go back to (e.g. opened from a notification), it goes to Tonight first.
 */
export type BackAction = "exit" | "history-back" | "go-tonight";

export function backAction(pathname: string, canGoBack: boolean): BackAction {
  if (pathname === "/") return "exit";
  return canGoBack ? "history-back" : "go-tonight";
}
