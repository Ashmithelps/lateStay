"use client";

import { useSyncExternalStore } from "react";
import { isNative } from "@/lib/native/platform";

const noop = () => () => {};

/** True only in the iOS/Android app, after hydration (the server and the website always render false). */
export function useNative(): boolean {
  return useSyncExternalStore(noop, isNative, () => false);
}
