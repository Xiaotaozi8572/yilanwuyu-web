package com.yilan.memory.application.privacy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ForgetSlaTest {
    @Test
    void fullAndSingleForgetCloseAuthorityBeforeFrozenBlockedReceiptsAndEvictL1() {
        var repository = new RecordingRepository();
        var cache = new RecordingCache();
        var service = new ForgetService(repository, cache, Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC));
        var subject = "s".repeat(43);

        var full = service.forgetAll(subject, "i".repeat(16));
        assertThat(full.state()).isEqualTo("BLOCKED");
        assertThat(repository.fullCommitted).isTrue();
        assertThat(cache.evictions).isEqualTo(1);

        var one = service.forgetOne(subject, UUID.randomUUID(), "j".repeat(16));
        assertThat(one.state()).isEqualTo("BLOCKED");
        assertThat(repository.singleCommitted).isTrue();
        assertThat(cache.evictions).isEqualTo(2);
    }

    private static final class RecordingRepository implements ForgetRepository {
        boolean fullCommitted; boolean singleCommitted;
        @Override public DeletionReceipt requestFull(String subjectHash, String key, Instant at) { fullCommitted = true; return new DeletionReceipt(UUID.randomUUID(), "BLOCKED", at); }
        @Override public DeletionReceipt requestSingle(String subjectHash, UUID assertionId, String key, Instant at) { singleCommitted = true; return new DeletionReceipt(UUID.randomUUID(), "BLOCKED", at); }
        @Override public boolean completeFull(UUID requestId, Instant executedAt) { return true; }
        @Override public DeletionVerifier.Verification verify(UUID requestId) { return new DeletionVerifier.Verification(requestId, DeletionVerifier.Scope.FULL, 0, 0, true, true); }
    }
    private static final class RecordingCache implements ForgetService.L1Evictor { int evictions; @Override public void evict(String subjectHash) { evictions++; } }
}
