"use client";

import { useEffect } from "react";
import { rememberFirstTouch } from "@/lib/attribution";

/** Keeps the UTM tags of the first visit that had any (see lib/attribution.ts). Renders nothing. */
export function AttributionCapture() {
  useEffect(() => {
    rememberFirstTouch(window.location.search);
  }, []);
  return null;
}
