package com.yilan.memory.application.privacy;

import java.time.Instant;

/** PostgreSQL authority maintenance boundary; every operation is bounded and idempotent. */
public interface RetentionRepository {

    default int eraseDueSourcePayloads(Instant now, int batchSize, String policyVersion) { return 0; }

    default int eraseDueAuditEvents(Instant now, int batchSize, String policyVersion) { return 0; }

    default int expireDueMemory(Instant now, int batchSize) { return 0; }

    default int staleDuePreferences(Instant now, int batchSize) { return 0; }
}
