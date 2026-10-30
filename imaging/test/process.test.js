import assert from "node:assert/strict";
import { test } from "node:test";
import sharp from "sharp";
import { processPhoto, sniff, WIDTHS } from "../src/process.js";

/** A 2000x1500 JPEG with camera EXIF and a GPS position. */
async function jpegWithGps({ width = 2000, height = 1500 } = {}) {
  return sharp({ create: { width, height, channels: 3, background: { r: 30, g: 107, b: 82 } } })
    .jpeg()
    .withExif({
      IFD0: { Make: "Phone Maker", Model: "Phone 12", Copyright: "Owner Name" },
      IFD3: {
        GPSLatitudeRef: "N",
        GPSLatitude: "32/1 14/1 3552/100",
        GPSLongitudeRef: "E",
        GPSLongitude: "77/1 11/1 2112/100",
      },
    })
    .toBuffer();
}

test("the test photo really carries EXIF and GPS", async () => {
  const input = await jpegWithGps();
  const meta = await sharp(input).metadata();
  assert.ok(meta.exif, "input has an EXIF block");
  assert.ok(meta.exif.includes(Buffer.from("Phone Maker")));
  // The GPS IFD pointer (tag 0x8825), in either byte order.
  assert.ok(meta.exif.includes(Buffer.from([0x88, 0x25])) || meta.exif.includes(Buffer.from([0x25, 0x88])), "input has GPS");
});

test("strips all EXIF, including GPS, from every variant", async () => {
  const input = await jpegWithGps();
  const { variants } = await processPhoto(input);
  assert.equal(variants.length, WIDTHS.length);
  for (const v of variants) {
    const meta = await sharp(v.data).metadata();
    assert.equal(meta.format, "webp");
    assert.equal(meta.exif, undefined, `${v.width}: no EXIF`);
    assert.equal(meta.xmp, undefined, `${v.width}: no XMP`);
    assert.equal(meta.iptc, undefined, `${v.width}: no IPTC`);
    assert.equal(meta.icc, undefined, `${v.width}: no ICC profile`);
    for (const marker of ["Exif", "EXIF", "GPS", "Phone Maker", "Owner Name", "XMP "]) {
      assert.ok(!v.data.includes(Buffer.from(marker)), `${v.width}: no "${marker}" bytes`);
    }
  }
});

test("makes WebP at 400, 800 and 1600 px wide, keeping the aspect ratio", async () => {
  const { width, height, variants } = await processPhoto(await jpegWithGps());
  assert.deepEqual([width, height], [2000, 1500]);
  assert.deepEqual(variants.map((v) => [v.width, v.actualWidth, v.height]), [[400, 400, 300], [800, 800, 600], [1600, 1600, 1200]]);
});

test("never enlarges a small photo", async () => {
  const { variants } = await processPhoto(await jpegWithGps({ width: 600, height: 400 }));
  assert.deepEqual(variants.map((v) => v.actualWidth), [400, 600, 600]);
});

test("applies the EXIF orientation to the pixels before dropping it", async () => {
  // Orientation 6: the camera was turned; the upright photo is portrait.
  const turned = await sharp(await jpegWithGps({ width: 1200, height: 800 })).withMetadata({ orientation: 6 }).toBuffer();
  assert.equal((await sharp(turned).metadata()).orientation, 6);
  const { width, height, variants } = await processPhoto(turned);
  assert.deepEqual([width, height], [800, 1200]);
  assert.equal((await sharp(variants[0].data).metadata()).orientation, undefined);
});

test("accepts PNG", async () => {
  const png = await sharp({ create: { width: 900, height: 600, channels: 4, background: "#ffffff" } }).png().toBuffer();
  const { variants } = await processPhoto(png);
  assert.equal(variants[1].actualWidth, 800);
});

test("rejects HEIC with instructions, and anything that isn't JPEG or PNG", async () => {
  const heic = Buffer.concat([Buffer.from([0, 0, 0, 24]), Buffer.from("ftypheic"), Buffer.alloc(32)]);
  assert.equal(sniff(heic), "heic");
  await assert.rejects(processPhoto(heic), { code: "HEIC", message: /Most Compatible/ });

  const gif = await sharp({ create: { width: 10, height: 10, channels: 3, background: "#000" } }).gif().toBuffer();
  await assert.rejects(processPhoto(gif), { code: "UNSUPPORTED" });
  // A "JPEG" by name only.
  await assert.rejects(processPhoto(Buffer.from("not really a jpeg")), { code: "UNSUPPORTED" });
  // JPEG magic bytes but broken data.
  await assert.rejects(processPhoto(Buffer.concat([Buffer.from([0xff, 0xd8, 0xff]), Buffer.alloc(100)])), { code: "UNREADABLE" });
});

test("rejects photos over 10 MB", async () => {
  const big = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff]), Buffer.alloc(10 * 1024 * 1024)]);
  await assert.rejects(processPhoto(big), { code: "TOO_LARGE", status: 413 });
});
