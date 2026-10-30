import assert from "node:assert/strict";
import { test } from "node:test";
import { allowNavigation, buildNumber } from "../app.config.mjs";

test("build numbers always grow with the version", () => {
  assert.equal(buildNumber("1.0.0"), 10000);
  assert.equal(buildNumber("1.2.3"), 10203);
  assert.ok(buildNumber("1.10.0") > buildNumber("1.9.99"));
  assert.throws(() => buildNumber("1.2"));
  assert.throws(() => buildNumber("1.100.0"));
});

test("the main view only shows our sites and Razorpay's checkout", () => {
  for (const host of allowNavigation) {
    assert.ok(/(^|\.)latestay\.in$/.test(host) || /^(checkout|api)\.razorpay\.com$/.test(host), host);
  }
});
