package com.yilan.memory.application.privacy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL authority boundary for immutable forget evidence and gates. */
public interface ForgetRepository {
    DeletionReceipt requestFull(String subjectHash, String idempotencyKey, Instant requestedAt);
    DeletionReceipt requestSingle(String subjectHash, UUID assertionId, String idempotencyKey, Instant requestedAt);
    default boolean completeFull(UUID requestId, Instant executedAt) {
        throw new UnsupportedOperationException("full deletion completion is unavailable");
    }
    default List<UUID> pendingFullRequests() { return List.of(); }
    DeletionVerifier.Verification verify(UUID requestId);
}
