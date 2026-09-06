package com.yilan.memory.application.consent;

import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Completes the local half of a committed policy-epoch change. PostgreSQL
 * persists the epoch; this service only makes pre-commit L1 state unusable
 * before the HTTP handler can return success.
 */
@Service
public final class PolicyEpochService {

    private final L1Invalidator l1Invalidator;

    /** The default is safe only when no process-local context cache is wired. */
    public PolicyEpochService() {
        this(L1Invalidator.disabled());
    }

    public PolicyEpochService(L1Invalidator l1Invalidator) {
        this.l1Invalidator = Objects.requireNonNull(l1Invalidator, "l1Invalidator");
    }

    public void invalidateAfterAuthorityCommit(String subjectHash, long consentEpoch) {
        if (subjectHash == null || subjectHash.isBlank() || consentEpoch < 1) {
            throw new IllegalArgumentException("authority commit");
        }
        l1Invalidator.evictForSubject(subjectHash);
    }

    @FunctionalInterface
    public interface L1Invalidator {
        void evictForSubject(String subjectHash);

        static L1Invalidator disabled() {
            return subjectHash -> { };
        }
    }
}
