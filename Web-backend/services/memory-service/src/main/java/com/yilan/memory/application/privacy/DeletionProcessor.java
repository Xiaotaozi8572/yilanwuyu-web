package com.yilan.memory.application.privacy;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Local completion seam: it records evidence only after the authority proves a full crypto-shred. */
public final class DeletionProcessor {
    private final ForgetRepository repository;
    private final Clock clock;
    public DeletionProcessor(ForgetRepository repository, Clock clock) { this.repository = Objects.requireNonNull(repository); this.clock = Objects.requireNonNull(clock); }
    public void complete(UUID requestId) {
        if (!repository.completeFull(requestId, clock.instant())) {
            throw new IllegalStateException("full forget request is not ready for completion");
        }
    }
    /** Attempts just the accepted receipt's completion without delaying its BLOCKED response. */
    public void tryComplete(UUID requestId) {
        try {
            complete(requestId);
        } catch (RuntimeException ignored) {
            // Leave the durable request without execution evidence so a later retry can close it.
        }
    }
    /** Completion is a durable best-effort queue; an authority receipt remains BLOCKED on retryable failure. */
    public void runOnce() {
        final Iterable<UUID> pending;
        try {
            pending = repository.pendingFullRequests();
        } catch (RuntimeException ignored) {
            return;
        }
        for (UUID requestId : pending) {
            tryComplete(requestId);
        }
    }
}
