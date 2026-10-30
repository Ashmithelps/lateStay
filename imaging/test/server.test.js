import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import sharp from "sharp";

process.env.IMAGING_TOKEN = "test-token";
const { server } = await import("../src/server.js");
let base;

before(async () => {
  await new Promise((resolve) => server.listen(0, resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test("needs the token", async () => {
  const res = await fetch(`${base}/process`, { method: "POST", body: Buffer.from("x") });
  assert.equal(res.status, 401);
});

test("returns base64 WebP variants", async () => {
  const jpeg = await sharp({ create: { width: 1000, height: 500, channels: 3, background: "#123456" } }).jpeg().toBuffer();
  const res = await fetch(`${base}/process`, { method: "POST", body: jpeg, headers: { Authorization: "Bearer test-token" } });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(body.variants.map((v) => v.width), [400, 800, 1600]);
  const meta = await sharp(Buffer.from(body.variants[0].webp, "base64")).metadata();
  assert.equal(meta.format, "webp");
});

test("answers HEIC with a clear 415", async () => {
  const heic = Buffer.concat([Buffer.from([0, 0, 0, 24]), Buffer.from("ftypheic"), Buffer.alloc(32)]);
  const res = await fetch(`${base}/process`, { method: "POST", body: heic, headers: { Authorization: "Bearer test-token" } });
  assert.equal(res.status, 415);
  assert.equal((await res.json()).code, "HEIC");
});
