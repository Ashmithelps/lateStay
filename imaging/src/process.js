import sharp from "sharp";

/** The widths every photo is published at. A smaller original isn't enlarged: its "1600" file is its own width. */
export const WIDTHS = [400, 800, 1600];
export const MAX_BYTES = 10 * 1024 * 1024;
const MAX_PIXELS = 60_000_000;

export class PhotoError extends Error {
  constructor(code, message, status = 415) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

/** What the bytes are, from their first bytes (never the file name or the client's Content-Type). */
export function sniff(bytes) {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "jpeg";
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return "png";
  }
  if (bytes.length >= 12 && bytes.subarray(4, 8).toString("latin1") === "ftyp") {
    const brand = bytes.subarray(8, 12).toString("latin1");
    if (["heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1"].includes(brand)) return "heic";
  }
  return "other";
}

/**
 * JPEG or PNG in, WebP out at each width. sharp writes no metadata unless asked (we never call withMetadata or
 * keepExif), so EXIF, GPS, XMP, IPTC and ICC data are all dropped; rotate() first applies the EXIF orientation to
 * the pixels so the photo stays upright without it.
 *
 * @returns {{width: number, height: number, variants: {width: number, actualWidth: number, height: number, data: Buffer}[]}}
 */
export async function processPhoto(bytes) {
  if (bytes.length > MAX_BYTES) throw new PhotoError("TOO_LARGE", "Photos can be up to 10 MB.", 413);
  const kind = sniff(bytes);
  if (kind === "heic") {
    throw new PhotoError(
      "HEIC",
      "HEIC photos aren't supported yet. On iPhone, choose Settings › Camera › Formats › Most Compatible, or share the photo as JPEG.",
    );
  }
  if (kind !== "jpeg" && kind !== "png") throw new PhotoError("UNSUPPORTED", "Only JPEG or PNG photos can be uploaded.");

  let upright;
  try {
    upright = await sharp(bytes, { failOn: "error", limitInputPixels: MAX_PIXELS }).rotate().toBuffer({ resolveWithObject: true });
  } catch {
    throw new PhotoError("UNREADABLE", "That photo couldn't be read. Try exporting it again as JPEG.", 422);
  }
  const { width, height } = upright.info;
  const variants = [];
  for (const target of WIDTHS) {
    const { data, info } = await sharp(upright.data)
      .resize({ width: target, withoutEnlargement: true })
      .webp({ quality: 80, effort: 4 })
      .toBuffer({ resolveWithObject: true });
    variants.push({ width: target, actualWidth: info.width, height: info.height, data });
  }
  return { width, height, variants };
}
