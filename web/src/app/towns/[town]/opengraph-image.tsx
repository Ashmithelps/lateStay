import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { ImageResponse } from "next/og";
import { fetchTown } from "@/lib/towns-api";

export const alt = "LateStay: late-night rooms";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";
export const revalidate = 3600;

const fonts = join(process.cwd(), "src/assets/fonts");

/** Plain, like the site: white canvas, ink text, one ember mark. No photo, no gradient. */
export default async function Image({ params }: { params: Promise<{ town: string }> }) {
  const { town: slug } = await params;
  const [regular, semibold, town] = await Promise.all([
    readFile(join(fonts, "plus-jakarta-sans-latin-400-normal.woff")),
    readFile(join(fonts, "plus-jakarta-sans-latin-600-normal.woff")),
    fetchTown(slug),
  ]);
  const name = town?.name ?? "the hills";
  const hotels = town?.hotels.length ?? 0;
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          background: "#FFFFFF",
          padding: "72px 80px",
          fontFamily: "Plus Jakarta Sans",
          color: "#222222",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
          <div style={{ width: 20, height: 20, borderRadius: 10, background: "#D9572B" }} />
          <div style={{ fontSize: 36, fontWeight: 600 }}>LateStay</div>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
          <div style={{ fontSize: 76, fontWeight: 600, lineHeight: 1.1 }}>{`Late-night rooms in ${name}`}</div>
          <div style={{ fontSize: 34, color: "#6A6A6A" }}>
            {hotels > 0
              ? `${hotels} partner ${hotels === 1 ? "hotel" : "hotels"} · agree a price with the desk · pay a small deposit`
              : "Agree a price with the desk · pay a small deposit"}
          </div>
        </div>
        <div style={{ height: 2, background: "#DDDDDD", width: "100%" }} />
      </div>
    ),
    {
      ...size,
      fonts: [
        { name: "Plus Jakarta Sans", data: regular, weight: 400, style: "normal" },
        { name: "Plus Jakarta Sans", data: semibold, weight: 600, style: "normal" },
      ],
    },
  );
}
