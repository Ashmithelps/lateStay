import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { MAX_BYTES, PhotoError, processPhoto } from "./process.js";

/*
 * POST /process with the photo as the raw body and "Authorization: Bearer <IMAGING_TOKEN>". Answers JSON:
 * { width, height, variants: [{ width, actualWidth, height, webp: base64 }] }, or { code, message } with 4xx.
 * GET /health for the platform's health check. Nothing is stored or logged about the photo.
 */

const PORT = Number(process.env.PORT ?? 8081);
const TOKEN = process.env.IMAGING_TOKEN ?? "";
if (!TOKEN && process.env.NODE_ENV === "production") {
  console.error("IMAGING_TOKEN is required in production");
  process.exit(1);
}

function authorised(header) {
  if (!TOKEN) return true; // local development only
  const expected = Buffer.from(`Bearer ${TOKEN}`);
  const given = Buffer.from(header ?? "");
  return given.length === expected.length && timingSafeEqual(given, expected);
}

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(json) });
  res.end(json);
}

export const server = createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") return send(res, 200, { status: "ok" });
  if (req.method !== "POST" || req.url !== "/process") return send(res, 404, { code: "NOT_FOUND", message: "Not found" });
  if (!authorised(req.headers.authorization)) return send(res, 401, { code: "UNAUTHORISED", message: "Bad token" });

  const chunks = [];
  let size = 0;
  let tooLarge = false;
  req.on("data", (chunk) => {
    size += chunk.length;
    if (size > MAX_BYTES) {
      tooLarge = true;
      return;
    }
    chunks.push(chunk);
  });
  req.on("end", async () => {
    if (tooLarge) return send(res, 413, { code: "TOO_LARGE", message: "Photos can be up to 10 MB." });
    try {
      const result = await processPhoto(Buffer.concat(chunks));
      send(res, 200, {
        width: result.width,
        height: result.height,
        variants: result.variants.map((v) => ({ width: v.width, actualWidth: v.actualWidth, height: v.height, webp: v.data.toString("base64") })),
      });
    } catch (e) {
      if (e instanceof PhotoError) return send(res, e.status, { code: e.code, message: e.message });
      console.error("processing failed", e instanceof Error ? e.message : e);
      send(res, 500, { code: "FAILED", message: "The photo couldn't be processed." });
    }
  });
});

if (import.meta.url === `file://${process.argv[1]}`) {
  server.listen(PORT, () => console.log(`imaging listening on ${PORT}`));
}
