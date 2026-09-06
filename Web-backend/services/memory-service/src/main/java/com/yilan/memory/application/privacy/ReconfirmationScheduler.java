package com.yilan.memory.application.privacy;

import java.time.Instant;
import java.util.Objects;

/** Marks due PREFERENCE heads stale; it never turns a stale head back on. */
public final class ReconfirmationScheduler {
    private final RetentionRepository repository;

    public ReconfirmationScheduler(RetentionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public int runOnce(Instant now, int batchSize) {
        RetentionScheduler.requireBatch(now, batchSize);
        return repository.staleDuePreferences(now, batchSize);
    }
}
