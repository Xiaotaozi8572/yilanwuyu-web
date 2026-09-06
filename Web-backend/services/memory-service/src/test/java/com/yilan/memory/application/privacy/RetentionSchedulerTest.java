package com.yilan.memory.application.privacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionSchedulerTest {

    @Test
    void runsFiniteRetentionBatchesWithoutAnyConfirmationAction() {
        var repository = new RetentionRepository() {
            @Override public int eraseDueSourcePayloads(Instant now, int batchSize, String policyVersion) { return 2; }
            @Override public int eraseDueAuditEvents(Instant now, int batchSize, String policyVersion) { return 1; }
            @Override public int expireDueMemory(Instant now, int batchSize) { return 3; }
        };

        var result = new RetentionScheduler(repository).runOnce(Instant.parse("2026-07-22T00:00:00Z"), 10);

        assertThat(result.sourcePayloadsErased()).isEqualTo(2);
        assertThat(result.auditEventsErased()).isEqualTo(1);
        assertThat(result.memoryHeadsExpired()).isEqualTo(3);
    }
}
