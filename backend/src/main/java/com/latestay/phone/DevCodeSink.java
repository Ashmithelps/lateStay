package com.latestay.phone;

/** Local profile only: keeps the last code per number so development can verify without WhatsApp. */
public interface DevCodeSink {

    void issued(String phone, String code);
}
