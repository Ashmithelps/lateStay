package com.latestay.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Records what would go to the Graph API; can fail the next sends. */
public class RecordingWhatsAppClient implements WhatsAppClient {

    public final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    public final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger ids = new AtomicInteger();

    @Override
    public boolean canSend() {
        return true;
    }

    @Override
    public String send(JsonNode message) {
        if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new WhatsAppSendException("Graph API 500: temporarily unavailable");
        }
        sent.add(message);
        return "wamid.test." + ids.incrementAndGet() + "." + System.nanoTime();
    }

    public void reset() {
        sent.clear();
        failNext.set(0);
    }
}
