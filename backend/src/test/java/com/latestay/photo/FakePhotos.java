package com.latestay.photo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test stand-ins: an imaging service that answers three small "WebP" files, and storage in a map. */
public final class FakePhotos {

    private FakePhotos() {
    }

    public static class Processor implements ImageProcessor {
        public final AtomicInteger calls = new AtomicInteger();

        @Override
        public Processed process(byte[] photo) {
            calls.incrementAndGet();
            return new Processed(2000, 1500, HotelPhotos.WIDTHS.stream()
                    .map(w -> new Variant(w, w, w * 3 / 4, ("RIFF-webp-" + w).getBytes())).toList());
        }
    }

    public static class Storage implements PhotoStorage {
        public final Map<String, byte[]> files = new ConcurrentHashMap<>();

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            files.put(key, bytes);
        }

        @Override
        public void delete(List<String> keys) {
            keys.forEach(files::remove);
        }

        @Override
        public String publicUrl(String key) {
            return "https://photos.test/" + key;
        }
    }
}
