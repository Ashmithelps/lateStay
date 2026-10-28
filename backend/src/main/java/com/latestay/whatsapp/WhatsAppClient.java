package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;

/** Sends one message through the WhatsApp Cloud API. */
public interface WhatsAppClient {

    /** False when no credentials are set: messages are logged as NOT_SENT instead. */
    boolean canSend();

    /**
     * @param message a Graph API /messages request body
     * @return the WhatsApp message id ("wamid...")
     * @throws WhatsAppSendException if Meta refuses or can't be reached
     */
    String send(JsonNode message);
}
