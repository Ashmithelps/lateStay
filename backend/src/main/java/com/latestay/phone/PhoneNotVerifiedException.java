package com.latestay.phone;

/** Booking needs the traveller's number verified on this device first. Maps to 403 PHONE_NOT_VERIFIED. */
public class PhoneNotVerifiedException extends RuntimeException {

    public PhoneNotVerifiedException() {
        super("verify your mobile number on WhatsApp first");
    }
}
