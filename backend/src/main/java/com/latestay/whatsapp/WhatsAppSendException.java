package com.latestay.whatsapp;

public class WhatsAppSendException extends RuntimeException {

    public WhatsAppSendException(String message) {
        super(message);
    }

    public WhatsAppSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
