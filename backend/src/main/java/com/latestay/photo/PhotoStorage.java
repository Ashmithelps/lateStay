package com.latestay.photo;

import java.util.List;

/** Where photo files live (S3-compatible). Keys look like hotels/<hotel id>/<photo id>-800.webp. */
public interface PhotoStorage {

    void put(String key, byte[] bytes, String contentType);

    void delete(List<String> keys);

    /** The public URL a browser loads the key from. */
    String publicUrl(String key);
}
