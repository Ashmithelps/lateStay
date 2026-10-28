package com.latestay.negotiation;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** State of one negotiation. Only {@link NegotiationEngine} changes it. */
public final class NegotiationSession {

    private final String id;
    private final PricingPolicy policy;
    private final LocalTime arrival;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final List<Turn> history = new ArrayList<>();
    private long currentOffer;
    private int round;
    private SessionStatus status = SessionStatus.OPEN;
    private Long agreedPrice;

    NegotiationSession(String id, PricingPolicy policy, LocalTime arrival, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.policy = policy;
        this.arrival = arrival;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String id() {
        return id;
    }

    public PricingPolicy policy() {
        return policy;
    }

    public LocalTime arrival() {
        return arrival;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public long currentOffer() {
        return currentOffer;
    }

    /** Number of bargaining turns (counters and best-price asks) taken so far. */
    public int round() {
        return round;
    }

    public SessionStatus status() {
        return status;
    }

    public Optional<Long> agreedPrice() {
        return Optional.ofNullable(agreedPrice);
    }

    public List<Turn> history() {
        return Collections.unmodifiableList(history);
    }

    void setCurrentOffer(long currentOffer) {
        this.currentOffer = currentOffer;
    }

    void nextRound() {
        round++;
    }

    void setStatus(SessionStatus status) {
        this.status = status;
    }

    void agree(long price) {
        this.agreedPrice = price;
        this.status = SessionStatus.AGREED;
    }

    void record(Turn turn) {
        history.add(turn);
    }
}
