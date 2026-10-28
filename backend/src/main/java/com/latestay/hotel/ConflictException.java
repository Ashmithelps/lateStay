package com.latestay.hotel;

/** The request is valid but can't be done in the current state. Maps to 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
