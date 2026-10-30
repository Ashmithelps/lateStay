package com.latestay.push;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Records every notification; tokens can be marked gone or failing. */
public class RecordingPushGateway implements PushGateway {

    public record Sent(String token, PushPayload payload) {
    }

    public final List<Sent> sent = new ArrayList<>();
    public final Set<String> gone = new HashSet<>();
    public final Set<String> failing = new HashSet<>();
    public volatile boolean configured = true;

    public synchronized void reset() {
        sent.clear();
        gone.clear();
        failing.clear();
        configured = true;
    }

    @Override
    public boolean canSend() {
        return configured;
    }

    @Override
    public synchronized Result send(String token, PushPayload payload) {
        if (gone.contains(token)) {
            return Result.INVALID_TOKEN;
        }
        if (failing.contains(token)) {
            return Result.FAILED;
        }
        sent.add(new Sent(token, payload));
        return Result.SENT;
    }

    public synchronized List<Sent> to(String token) {
        return sent.stream().filter(s -> s.token().equals(token)).toList();
    }
}
