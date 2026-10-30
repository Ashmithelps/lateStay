import "server-only";
import { headers } from "next/headers";
import { notFound } from "next/navigation";
import { isAppUserAgent } from "./platform";

/** The app's own pages (/app/*) don't exist on the website. */
export async function appOnly(): Promise<void> {
  if (!isAppUserAgent((await headers()).get("user-agent"))) notFound();
}
