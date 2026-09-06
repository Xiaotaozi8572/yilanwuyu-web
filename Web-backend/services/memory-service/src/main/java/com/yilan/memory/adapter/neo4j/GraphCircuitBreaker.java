package com.yilan.memory.adapter.neo4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Local fail-open guard for the non-authoritative graph projection. */
public final class GraphCircuitBreaker {

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private int consecutiveFailures;
    private Instant openUntil = Instant.EPOCH;

    public GraphCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = requirePositive(openDuration, "openDuration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GraphCircuitBreaker() {
        this(2, Duration.ofSeconds(30), Clock.systemUTC());
    }

    public synchronized boolean allowRequest() {
        return !Instant.now(clock).isBefore(openUntil);
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = Instant.EPOCH;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openUntil = Instant.now(clock).plus(openDuration);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }
}
