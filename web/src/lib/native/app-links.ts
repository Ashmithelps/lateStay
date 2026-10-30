/**
 * Universal Links (iOS) and App Links (Android) for /pass/* and /stay/*: those links open in the app when it's
 * installed and in the browser otherwise. Each file is served only once its values are configured.
 */
export const DEEP_LINK_PATHS = ["/pass/*", "/stay/*"];
export const DEFAULT_APP_ID = "app.latestay";

export function appleAppSiteAssociation(teamId: string | undefined, appId: string | undefined) {
  if (!teamId?.trim()) return null;
  return {
    applinks: {
      details: [
        {
          appIDs: [`${teamId.trim()}.${appId?.trim() || DEFAULT_APP_ID}`],
          components: DEEP_LINK_PATHS.map((path) => ({ "/": path })),
        },
      ],
    },
  };
}

/** @param fingerprints SHA-256 certificate fingerprints, comma-separated (upload key and Play app-signing key) */
export function assetLinks(fingerprints: string | undefined, appId: string | undefined) {
  const sha = (fingerprints ?? "").split(",").map((f) => f.trim().toUpperCase()).filter(Boolean);
  if (sha.length === 0) return null;
  return [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: { namespace: "android_app", package_name: appId?.trim() || DEFAULT_APP_ID, sha256_cert_fingerprints: sha },
    },
  ];
}
