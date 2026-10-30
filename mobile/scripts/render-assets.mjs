/**
 * Draws the icon and splash sources in assets/ from HTML, with the app's font (Playwright, from web/'s
 * node_modules): a white "L" on an ink square; the splash is white with the LateStay wordmark in ink. No gradients.
 * Then `npm run assets` makes every size for iOS and Android. Run: node scripts/render-assets.mjs
 */
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(new URL("../../web/package.json", import.meta.url));
const { chromium } = require("@playwright/test");
const font = (w) => readFileSync(new URL(`../www/fonts/plus-jakarta-sans-latin-${w}-normal.woff`, import.meta.url)).toString("base64");
const css = `@font-face{font-family:J;font-weight:600;src:url(data:font/woff;base64,${font(600)}) format("woff")}
  html,body{margin:0}*{box-sizing:border-box}`;

/** A white L centred on its ink, not its text box (glyph metrics aren't symmetric). */
function letter(size, fontSize, background) {
  return `<canvas id="c" width="${size}" height="${size}" style="display:block;background:${background}"></canvas>
  <script>
    document.fonts.load("600 ${fontSize}px J").then(() => {
      const c = document.getElementById("c").getContext("2d");
      c.font = "600 ${fontSize}px J";
      const m = c.measureText("L");
      const w = m.actualBoundingBoxLeft + m.actualBoundingBoxRight;
      const h = m.actualBoundingBoxAscent + m.actualBoundingBoxDescent;
      c.fillStyle = "#ffffff";
      c.fillText("L", (${size} - w) / 2 + m.actualBoundingBoxLeft, (${size} - h) / 2 + m.actualBoundingBoxAscent);
      document.body.dataset.done = "1";
    });
  </script>`;
}

const pages = {
  // App Store / iOS: the whole square (the system rounds the corners).
  "icon-only.png": [1024, letter(1024, 600, "#222222")],
  // Android adaptive icon: the L inside the 66% safe zone, on transparent; the background is plain ink.
  "icon-foreground.png": [1024, letter(1024, 400, "transparent")],
  "icon-background.png": [1024, `<div style="width:1024px;height:1024px;background:#222222"></div>`],
  "splash.png": [2732, `<div style="width:2732px;height:2732px;background:#fff;display:flex;align-items:center;justify-content:center;font:600 200px/1 J;color:#222222">LateStay</div>`],
  "splash-dark.png": [2732, `<div style="width:2732px;height:2732px;background:#fff;display:flex;align-items:center;justify-content:center;font:600 200px/1 J;color:#222222">LateStay</div>`],
};

const browser = await chromium.launch();
for (const [name, [size, html]] of Object.entries(pages)) {
  const page = await browser.newPage({ viewport: { width: size, height: size } });
  await page.setContent(`<style>${css}</style>${html}`);
  await page.evaluate(() => document.fonts.ready);
  if (html.includes("<canvas")) await page.waitForFunction(() => document.body.dataset.done === "1");
  await page.screenshot({ path: new URL(`../assets/${name}`, import.meta.url).pathname, omitBackground: name === "icon-foreground.png" });
  await page.close();
}
await browser.close();
console.log("assets/ written");
