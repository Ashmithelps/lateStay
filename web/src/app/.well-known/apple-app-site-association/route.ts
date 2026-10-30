import { appleAppSiteAssociation } from "@/lib/native/app-links";

export const dynamic = "force-dynamic";

/** iOS Universal Links for /pass/* and /stay/* (APPLE_TEAM_ID, MOBILE_APP_ID). 404 until configured. */
export function GET() {
  const body = appleAppSiteAssociation(process.env.APPLE_TEAM_ID, process.env.MOBILE_APP_ID);
  if (!body) return new Response("Not found", { status: 404 });
  return Response.json(body, { headers: { "Cache-Control": "public, max-age=3600" } });
}
