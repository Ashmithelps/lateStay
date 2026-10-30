package com.latestay.negotiation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic haggling rules. Every price a traveller ever sees comes from here.
 *
 * <p>The floor price only ever leaves this class as an offer amount; no decision exposes it any other way.
 */
public final class NegotiationEngine {

    public static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(20);

    /** A counter at or above the floor and this close to the current offer is agreed outright. */
    static final long CLOSE_ENOUGH_MARGIN = 50;

    private final Clock clock;
    private final Duration sessionTtl;

    public NegotiationEngine(Clock clock) {
        this(clock, DEFAULT_SESSION_TTL);
    }

    public NegotiationEngine(Clock clock, Duration sessionTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
    }

    /** Opens a session for a traveller arriving now. */
    public NegotiationSession open(PricingPolicy policy) {
        return open(policy, LocalTime.now(clock));
    }

    public NegotiationSession open(PricingPolicy policy, LocalTime arrival) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(arrival, "arrival");
        if (!policy.slotType().accepts(arrival)) {
            throw new IllegalArgumentException("Arrival " + arrival + " is outside the " + policy.slotType()
                    + " window " + policy.slotType().arriveFrom() + "-" + policy.slotType().arriveUntil());
        }

        Instant now = clock.instant();
        NegotiationSession session = new NegotiationSession(
                UUID.randomUUID().toString(), policy, arrival, now, now.plus(sessionTtl));

        long opening = OpeningPrice.amount(policy, arrival);
        session.setCurrentOffer(opening);

        EngineDecision decision;
        if (opening == policy.effectiveFloor()) {
            session.setStatus(SessionStatus.FINAL_OFFER);
            decision = new EngineDecision(EngineAction.FINAL_OFFER, opening, ReasonCode.FLOOR_REACHED);
        } else {
            ReasonCode reason = OpeningPrice.percentOfList(arrival) == 90 ? ReasonCode.OPENING : ReasonCode.LATE_HOUR;
            decision = new EngineDecision(EngineAction.OFFER, opening, reason);
        }
        session.record(new Turn(0, Optional.empty(), decision, now));
        return session;
    }

    /** Rebuilds a session from stored state. */
    public NegotiationSession restore(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        NegotiationSession session = new NegotiationSession(snapshot.id(), snapshot.policy(), snapshot.arrival(),
                snapshot.createdAt(), snapshot.expiresAt());
        session.restoreState(snapshot.currentOffer(), snapshot.round(), snapshot.status(), snapshot.agreedPrice());
        return session;
    }

    /** The price a session would open at, without opening one. Used to show "from" prices in search. */
    public long openingOffer(PricingPolicy policy, LocalTime arrival) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(arrival, "arrival");
        if (!policy.slotType().accepts(arrival)) {
            throw new IllegalArgumentException("Arrival " + arrival + " is outside the " + policy.slotType() + " window");
        }
        return OpeningPrice.amount(policy, arrival);
    }

    /**
     * Applies one traveller turn.
     *
     * @throws SessionClosedException if the session has ended or has just expired
     */
    public EngineDecision respond(NegotiationSession session, TravellerEvent event) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(event, "event");

        synchronized (session) {
            Instant now = clock.instant();
            ensureOpen(session);

            EngineDecision decision = session.status() == SessionStatus.FINAL_OFFER
                    ? respondToFinal(session, event)
                    : respondWhileOpen(session, event);
            session.record(new Turn(session.round(), Optional.of(event), decision, now));
            return decision;
        }
    }

    /**
     * Marks the session expired if its time is up, then throws if it has ended. For callers that answer the
     * traveller without moving the price.
     *
     * @throws SessionClosedException if the session has ended or has just expired
     */
    public void ensureOpen(NegotiationSession session) {
        synchronized (session) {
            if (!session.status().isTerminal() && clock.instant().isAfter(session.expiresAt())) {
                session.setStatus(SessionStatus.EXPIRED);
            }
            if (session.status().isTerminal()) {
                throw new SessionClosedException(session.id(), session.status());
            }
        }
    }

    private EngineDecision respondWhileOpen(NegotiationSession session, TravellerEvent event) {
        PricingPolicy policy = session.policy();
        long offer = session.currentOffer();

        return switch (event) {
            case TravellerEvent.Accept accept -> agree(session, offer, ReasonCode.ACCEPTED);
            case TravellerEvent.Other other -> hold(offer, ReasonCode.UNCLEAR);
            case TravellerEvent.AskBestPrice ask -> {
                session.nextRound();
                yield concede(session, policy.effectiveFloor(), ReasonCode.MEETING_HALFWAY);
            }
            case TravellerEvent.Counter counter -> {
                session.nextRound();
                long amount = counter.amount();
                if (amount >= offer) {
                    yield agree(session, offer, ReasonCode.ACCEPTED);
                }
                if (amount >= policy.floorPrice() && offer - amount <= CLOSE_ENOUGH_MARGIN) {
                    yield agree(session, Prices.roundUp(amount, policy.roundTo()), ReasonCode.CLOSE_ENOUGH);
                }
                long target = Math.max(amount, policy.effectiveFloor());
                ReasonCode reason = amount < policy.floorPrice() ? ReasonCode.COUNTER_TOO_LOW : ReasonCode.MEETING_HALFWAY;
                yield concede(session, target, reason);
            }
        };
    }

    private EngineDecision respondToFinal(NegotiationSession session, TravellerEvent event) {
        long finalOffer = session.currentOffer();

        return switch (event) {
            case TravellerEvent.Accept accept -> agree(session, finalOffer, ReasonCode.ACCEPTED);
            case TravellerEvent.Other other -> hold(finalOffer, ReasonCode.UNCLEAR);
            case TravellerEvent.Counter counter when counter.amount() >= finalOffer ->
                    agree(session, finalOffer, ReasonCode.ACCEPTED);
            case TravellerEvent.Counter counter -> noDeal(session, finalOffer);
            case TravellerEvent.AskBestPrice ask -> noDeal(session, finalOffer);
        };
    }

    private EngineDecision concede(NegotiationSession session, long target, ReasonCode reason) {
        PricingPolicy policy = session.policy();
        long floor = policy.effectiveFloor();
        long next = Prices.concede(
                session.currentOffer(), target, Prices.stepPercent(session.round(), policy.context()), policy.roundTo(), floor);
        session.setCurrentOffer(next);

        if (next == floor) {
            session.setStatus(SessionStatus.FINAL_OFFER);
            return new EngineDecision(EngineAction.FINAL_OFFER, next, ReasonCode.FLOOR_REACHED);
        }
        if (session.round() >= policy.maxRounds()) {
            session.setStatus(SessionStatus.FINAL_OFFER);
            return new EngineDecision(EngineAction.FINAL_OFFER, next, reason);
        }
        return new EngineDecision(EngineAction.OFFER, next, reason);
    }

    private static EngineDecision agree(NegotiationSession session, long price, ReasonCode reason) {
        session.setCurrentOffer(price);
        session.agree(price);
        return new EngineDecision(EngineAction.AGREE, price, reason);
    }

    private static EngineDecision hold(long offer, ReasonCode reason) {
        return new EngineDecision(EngineAction.HOLD, offer, reason);
    }

    private static EngineDecision noDeal(NegotiationSession session, long finalOffer) {
        session.setStatus(SessionStatus.NO_DEAL);
        return hold(finalOffer, ReasonCode.ALREADY_FINAL);
    }
}
