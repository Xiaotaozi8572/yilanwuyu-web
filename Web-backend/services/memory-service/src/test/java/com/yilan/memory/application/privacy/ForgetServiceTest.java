package com.yilan.memory.application.privacy;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ForgetServiceTest {

    @Test
    void acceptedFullForgetBlocksBeforeReturningAndEvictsL1() {
        var repository = new RecordingRepository();
        var cache = new RecordingCache();
        var service = new ForgetService(repository, cache, Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC));

        var receipt = service.forgetAll("s".repeat(43), "i".repeat(16));

        assertThat(receipt.state()).isEqualTo("BLOCKED");
        assertThat(repository.fullBlocked).isTrue();
        assertThat(repository.fullCompleted).isTrue();
        assertThat(cache.evicted).isTrue();
    }

    @Test
    void fullForgetEvictsL1AndKeepsItsBlockedReceiptWhenCompletionIsPending() {
        var repository = new RecordingRepository();
        repository.completionFails = true;
        repository.pendingQueryFails = true;
        var cache = new RecordingCache();
        var service = new ForgetService(repository, cache, Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC));

        var receipt = service.forgetAll("s".repeat(43), "i".repeat(16));

        assertThat(repository.fullBlocked).isTrue();
        assertThat(cache.evicted).isTrue();
        assertThat(receipt.state()).isEqualTo("BLOCKED");
        assertThat(repository.fullCompleted).isTrue();
        assertThat(repository.pendingQueries).isZero();

        new DeletionProcessor(repository, Clock.systemUTC()).runOnce();
        assertThat(repository.fullCompleted).isTrue();

        repository.completionFails = false;
        var retry = service.forgetAll("s".repeat(43), "i".repeat(16));
        assertThat(retry.requestId()).isEqualTo(receipt.requestId());
        assertThat(repository.closed).isTrue();
    }

    @Test
    void singleForgetCreatesSubjectBoundTombstone() {
        var repository = new RecordingRepository();
        var service = new ForgetService(repository, new RecordingCache(), Clock.systemUTC());

        var receipt = service.forgetOne("s".repeat(43), UUID.randomUUID(), "i".repeat(16));

        assertThat(receipt.state()).isEqualTo("BLOCKED");
        assertThat(repository.singleTombstoned).isTrue();
    }

    private static final class RecordingRepository implements ForgetRepository {
        boolean fullBlocked;
        boolean singleTombstoned;
        boolean fullCompleted;
        boolean completionFails;
        boolean pendingQueryFails;
        boolean closed;
        int pendingQueries;
        final UUID fullRequestId = UUID.randomUUID();
        @Override public DeletionReceipt requestFull(String subjectHash, String idempotencyKey, Instant requestedAt) {
            fullBlocked = true;
            return new DeletionReceipt(fullRequestId, "BLOCKED", requestedAt);
        }
        @Override public DeletionReceipt requestSingle(String subjectHash, UUID assertionId, String idempotencyKey, Instant requestedAt) {
            singleTombstoned = true;
            return new DeletionReceipt(UUID.randomUUID(), "BLOCKED", requestedAt);
        }
        @Override public boolean completeFull(UUID requestId, Instant executedAt) { fullCompleted = true; closed = !completionFails; return closed; }
        @Override public java.util.List<UUID> pendingFullRequests() {
            pendingQueries++;
            if (pendingQueryFails) throw new IllegalStateException("pending query unavailable");
            return closed ? java.util.List.of() : java.util.List.of(fullRequestId);
        }
        @Override public DeletionVerifier.Verification verify(UUID requestId) { return new DeletionVerifier.Verification(requestId, DeletionVerifier.Scope.FULL, closed ? 0 : 1, 0, closed, closed); }
    }

    private static final class RecordingCache implements ForgetService.L1Evictor {
        boolean evicted;
        @Override public void evict(String subjectHash) { evicted = true; }
    }
}
