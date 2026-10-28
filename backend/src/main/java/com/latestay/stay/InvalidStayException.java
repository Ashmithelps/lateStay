package com.latestay.stay;

/** A search or booking request that LateStay can't serve as asked. Maps to 400. */
public class InvalidStayException extends RuntimeException {

    public InvalidStayException(String message) {
        super(message);
    }
}
