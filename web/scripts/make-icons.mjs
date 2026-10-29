// Draws the app icons: a white "L" on an ink (#222222) square. Flat colours only.
// Run with `node scripts/make-icons.mjs`; the PNGs are checked in under public/icons/.
import sharp from "sharp";
import { writeFileSync } from "node:fs";

/** The L as two rectangles inside a square of `size`, scaled to fill `fill` of it (smaller for maskable). */
function svg(size, fill) {
  const box = size * fill;
  const x0 = (size - box) / 2;
  const y0 = (size - box) / 2;
  const stroke = box * 0.15;
  const left = x0 + box * 0.3;
  const top = y0 + box * 0.14;
  const height = box * 0.72;
  const width = box * 0.42;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
  <rect width="${size}" height="${size}" fill="#222222"/>
  <rect x="${left}" y="${top}" width="${stroke}" height="${height}" fill="#FFFFFF"/>
  <rect x="${left}" y="${top + height - stroke}" width="${width}" height="${stroke}" fill="#FFFFFF"/>
</svg>`;
}

const out = "public/icons";
const icons = [
  ["icon-192.png", 192, 1],
  ["icon-512.png", 512, 1],
  ["icon-maskable-512.png", 512, 0.7],
  ["apple-touch-icon.png", 180, 1],
];
for (const [name, size, fill] of icons) {
  await sharp(Buffer.from(svg(size, fill))).png().toFile(`${out}/${name}`);
}
writeFileSync("src/app/icon.svg", svg(64, 1));
console.log("icons written");
