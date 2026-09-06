package com.yilan.memory.application.privacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReconfirmationSchedulerTest {

    @Test
    void onlyStalesDuePreferencesAndNeverAutoConfirms() {
        var repository = new RetentionRepository() {
            @Override public int staleDuePreferences(Instant now, int batchSize) { return 1; }
        };

        assertThat(new ReconfirmationScheduler(repository).runOnce(Instant.parse("2026-07-22T00:00:00Z"), 4))
                .isEqualTo(1);
    }
}
