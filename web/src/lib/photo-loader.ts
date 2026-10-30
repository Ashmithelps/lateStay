import type { ImageLoaderProps } from "next/image";

/** Uploaded photos are stored as WebP at these widths: <key>-400.webp, -800.webp, -1600.webp. */
export const PHOTO_WIDTHS = [400, 800, 1600] as const;
const UPLOADED = /-(400|800|1600)\.webp$/;

/** True for a photo uploaded through /admin (already resized and EXIF-free in our storage). */
export function isUploadedPhoto(src: string): boolean {
  return UPLOADED.test(src);
}

/**
 * Picks the stored variant for the width next/image asks for, so uploaded photos come straight from storage
 * (cached for a year) instead of being resized again.
 */
export function uploadedPhotoLoader({ src, width }: ImageLoaderProps): string {
  const variant = PHOTO_WIDTHS.find((w) => w >= width) ?? 1600;
  return src.replace(UPLOADED, `-${variant}.webp`);
}
