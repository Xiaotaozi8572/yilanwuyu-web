package com.yilan.memory.application.async;

import java.time.Duration;

/** Bounded, deterministic retry timing for durable async work. */
public final class RetryPolicy {

    public static final int MAX_ATTEMPTS = 5;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private RetryPolicy() {
    }

    public static Duration backoffForAttempt(int attempt) {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attempt must be within 1.." + MAX_ATTEMPTS);
        }
        long seconds = 1L << Math.min(attempt, 8);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }

    public static boolean isTerminal(int attempt) {
        return attempt >= MAX_ATTEMPTS;
    }
}
