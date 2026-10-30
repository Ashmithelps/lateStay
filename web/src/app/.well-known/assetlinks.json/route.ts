import { assetLinks } from "@/lib/native/app-links";

export const dynamic = "force-dynamic";

/** Android App Links for /pass/* and /stay/* (ANDROID_CERT_SHA256, MOBILE_APP_ID). 404 until configured. */
export function GET() {
  const body = assetLinks(process.env.ANDROID_CERT_SHA256, process.env.MOBILE_APP_ID);
  if (!body) return new Response("Not found", { status: 404 });
  return Response.json(body, { headers: { "Cache-Control": "public, max-age=3600" } });
}
