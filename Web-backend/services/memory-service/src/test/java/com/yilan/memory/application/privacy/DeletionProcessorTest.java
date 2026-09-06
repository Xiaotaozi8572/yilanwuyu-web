package com.yilan.memory.application.privacy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeletionProcessorTest {
    @Test
    void completionWritesOnlyRepositoryVerifiedFullCryptoShredEvidence() {
        var repository = new RecordingRepository();
        var request = UUID.randomUUID();
        var now = Instant.parse("2026-07-22T00:00:00Z");
        new DeletionProcessor(repository, Clock.fixed(now, ZoneOffset.UTC)).complete(request);

        assertThat(repository.executionRequest).isEqualTo(request);
        assertThat(repository.executedAt).isEqualTo(now);
        assertThat(new DeletionVerifier(repository).verify(request))
                .isEqualTo(new DeletionVerifier.Verification(
                        request, DeletionVerifier.Scope.FULL, 0, 0, true, true));
    }

    @Test
    void failedCompletionRemainsPendingAndAPostedRetryClosesTheVerification() {
        var repository = new QueueRepository();
        var processor = new DeletionProcessor(repository, Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC));

        processor.runOnce();
        assertThat(repository.completed).isFalse();
        assertThat(new DeletionVerifier(repository).verify(repository.request).closed()).isFalse();

        repository.fail = false;
        processor.runOnce();
        assertThat(repository.completed).isTrue();
        assertThat(new DeletionVerifier(repository).verify(repository.request).closed()).isTrue();
    }

    @Test
    void pendingQueueQueryFailureIsBestEffortAndDoesNotEscapeTheRetryEntryPoint() {
        var repository = new QueueRepository();
        repository.pendingQueryFails = true;
        var processor = new DeletionProcessor(repository, Clock.systemUTC());

        processor.runOnce();

        assertThat(repository.completed).isFalse();

        repository.pendingQueryFails = false;
        repository.fail = false;
        processor.runOnce();
        assertThat(repository.completed).isTrue();
    }

    private static final class RecordingRepository implements ForgetRepository {
        UUID executionRequest; Instant executedAt;
        @Override public DeletionReceipt requestFull(String subjectHash, String idempotencyKey, Instant requestedAt) { throw new UnsupportedOperationException(); }
        @Override public DeletionReceipt requestSingle(String subjectHash, UUID assertionId, String idempotencyKey, Instant requestedAt) { throw new UnsupportedOperationException(); }
        @Override public boolean completeFull(UUID requestId, Instant at) { executionRequest = requestId; executedAt = at; return true; }
        @Override public DeletionVerifier.Verification verify(UUID requestId) { return new DeletionVerifier.Verification(requestId, DeletionVerifier.Scope.FULL, 0, 0, true, true); }
    }

    private static final class QueueRepository implements ForgetRepository {
        final UUID request = UUID.randomUUID(); boolean fail = true; boolean completed; boolean pendingQueryFails;
        @Override public DeletionReceipt requestFull(String subjectHash, String idempotencyKey, Instant requestedAt) { throw new UnsupportedOperationException(); }
        @Override public DeletionReceipt requestSingle(String subjectHash, UUID assertionId, String idempotencyKey, Instant requestedAt) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<UUID> pendingFullRequests() {
            if (pendingQueryFails) throw new IllegalStateException("pending query unavailable");
            return completed ? java.util.List.of() : java.util.List.of(request);
        }
        @Override public boolean completeFull(UUID requestId, Instant at) { completed = !fail; return completed; }
        @Override public DeletionVerifier.Verification verify(UUID requestId) { return new DeletionVerifier.Verification(requestId, DeletionVerifier.Scope.FULL, completed ? 0 : 1, 0, completed, completed); }
    }
}
