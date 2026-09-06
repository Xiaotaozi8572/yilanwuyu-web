package com.yilan.memory.application.privacy;

import java.time.Instant;
import java.util.Objects;

/** Clock-driven authority maintenance method; deliberately not a deployment scheduler. */
public final class RetentionScheduler {
    private final RetentionRepository repository;

    public RetentionScheduler(RetentionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Result runOnce(Instant now, int batchSize) {
        requireBatch(now, batchSize);
        return new Result(
                repository.eraseDueSourcePayloads(now, batchSize, RetentionPolicy.VERSION),
                repository.eraseDueAuditEvents(now, batchSize, RetentionPolicy.VERSION),
                repository.expireDueMemory(now, batchSize));
    }

    static void requireBatch(Instant now, int batchSize) {
        Objects.requireNonNull(now, "now");
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize");
        }
    }

    public record Result(int sourcePayloadsErased, int auditEventsErased, int memoryHeadsExpired) {
        public Result {
            if (sourcePayloadsErased < 0 || auditEventsErased < 0 || memoryHeadsExpired < 0) {
                throw new IllegalArgumentException("retention counts");
            }
        }
    }
}
