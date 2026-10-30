package com.latestay.photo;

/** An upload we won't take (HEIC, not a photo, too big): 4xx with a message the admin can act on. */
public class PhotoRejectedException extends RuntimeException {

    private final String code;
    private final int status;

    public PhotoRejectedException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
