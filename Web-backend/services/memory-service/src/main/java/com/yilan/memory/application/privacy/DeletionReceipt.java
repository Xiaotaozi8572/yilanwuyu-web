package com.yilan.memory.application.privacy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Frozen API receipt; contains identifiers and state only. */
public record DeletionReceipt(UUID requestId, String state, Instant requestedAt) {
    public DeletionReceipt {
        requestId = Objects.requireNonNull(requestId, "requestId");
        if (!"BLOCKED".equals(state)) throw new IllegalArgumentException("state");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
