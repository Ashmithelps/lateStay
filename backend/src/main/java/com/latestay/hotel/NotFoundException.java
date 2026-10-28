package com.latestay.hotel;

/** A referenced hotel, room type, rule, stay request or session doesn't exist. Maps to 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object id) {
        super(what + " " + id + " not found");
    }
}
