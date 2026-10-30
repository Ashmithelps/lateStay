package com.latestay.photo;

import java.util.List;

/** Turns an uploaded JPEG or PNG into EXIF-free WebP variants (the imaging service). */
public interface ImageProcessor {

    /** One WebP file: {@code width} is the nominal width (400, 800, 1600) in its name; actualWidth may be less. */
    record Variant(int width, int actualWidth, int height, byte[] webp) {
    }

    record Processed(int width, int height, List<Variant> variants) {
    }

    /** @throws PhotoRejectedException for HEIC, other formats, unreadable or oversized files */
    Processed process(byte[] photo);
}
