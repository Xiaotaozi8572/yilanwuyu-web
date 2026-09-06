package com.yilan.memory.domain.consent;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * A current, server-side consent policy. Missing or invalid state is deliberately
 * represented so every caller can deny by default with a stable reason code.
 */
public record ConsentPolicy(
        SubjectStatus subjectStatus,
        PolicyStatus policyStatus,
        long revision,
        Set<MemoryCategory> allowedCategories,
        Instant validFrom,
        Instant validUntil) {

    public ConsentPolicy {
        Objects.requireNonNull(subjectStatus, "subjectStatus");
        Objects.requireNonNull(policyStatus, "policyStatus");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        allowedCategories = Set.copyOf(Objects.requireNonNull(allowedCategories, "allowedCategories"));
        if (policyStatus != PolicyStatus.MISSING && validFrom == null) {
            throw new IllegalArgumentException("validFrom is required for a persisted policy");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
    }

    public static ConsentPolicy missing(SubjectStatus subjectStatus) {
        return new ConsentPolicy(subjectStatus, PolicyStatus.MISSING, 0, Set.of(), null, null);
    }

    public enum SubjectStatus {
        ACTIVE,
        DISABLED
    }

    public enum PolicyStatus {
        MISSING,
        ACTIVE,
        REVOKED,
        EXPIRED
    }

    /**
     * Categories are policy scopes, never a source of aviation facts.
     */
    public enum MemoryCategory {
        PREFERENCE,
        MASTERY,
        MISCONCEPTION,
        REFLECTION
    }

    public enum DenialReason {
        MISSING_POLICY,
        SUBJECT_DISABLED,
        STALE_REVISION,
        CATEGORY_DENIED,
        NOT_YET_VALID,
        TIME_INVALID,
        POLICY_INACTIVE
    }

    public record ConsentDecision(boolean allowed, DenialReason denialReason) {

        public ConsentDecision {
            if (allowed && denialReason != null) {
                throw new IllegalArgumentException("allowed decision cannot include a denial reason");
            }
            if (!allowed && denialReason == null) {
                throw new IllegalArgumentException("denied decision requires a reason");
            }
        }

        public static ConsentDecision denied(DenialReason reason) {
            return new ConsentDecision(false, Objects.requireNonNull(reason, "reason"));
        }
    }
}
