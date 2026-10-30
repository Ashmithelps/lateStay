import { describe, expect, it } from "vitest";
import { appleAppSiteAssociation, assetLinks } from "./app-links";

describe("app links", () => {
  it("serves iOS Universal Links for passes and shared stays", () => {
    expect(appleAppSiteAssociation(undefined, undefined)).toBeNull();
    expect(appleAppSiteAssociation("ABCDE12345", undefined)).toEqual({
      applinks: {
        details: [{ appIDs: ["ABCDE12345.app.latestay"], components: [{ "/": "/pass/*" }, { "/": "/stay/*" }] }],
      },
    });
    expect(appleAppSiteAssociation("ABCDE12345", "in.latestay.staging")!.applinks.details[0].appIDs).toEqual([
      "ABCDE12345.in.latestay.staging",
    ]);
  });
  it("serves Android App Links with every signing fingerprint", () => {
    expect(assetLinks("", undefined)).toBeNull();
    const links = assetLinks("aa:bb, cc:dd", undefined)!;
    expect(links[0].relation).toEqual(["delegate_permission/common.handle_all_urls"]);
    expect(links[0].target).toEqual({
      namespace: "android_app",
      package_name: "app.latestay",
      sha256_cert_fingerprints: ["AA:BB", "CC:DD"],
    });
  });
});
