package com.yilan.memory.domain.identity;

/**
 * A subject binding established exclusively from verified transport metadata.
 */
public record LearnerIdentity(String subjectHash, String sessionId, long consentRevision) {

    public LearnerIdentity {
        if (subjectHash == null || subjectHash.isBlank()) {
            throw new IllegalArgumentException("subjectHash must be non-blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must be non-blank");
        }
        if (consentRevision < 0) {
            throw new IllegalArgumentException("consentRevision must be non-negative");
        }
    }
}
