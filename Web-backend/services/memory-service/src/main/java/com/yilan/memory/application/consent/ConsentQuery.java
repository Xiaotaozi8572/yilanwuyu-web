package com.yilan.memory.application.consent;

import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.consent.ConsentPolicy.ConsentDecision;
import com.yilan.memory.domain.consent.ConsentPolicy.DenialReason;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.consent.ConsentPolicy.PolicyStatus;
import com.yilan.memory.domain.consent.ConsentPolicy.SubjectStatus;
import com.yilan.memory.domain.identity.LearnerIdentity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only consent gate. The signed identity revision must match the current
 * policy revision; historical event consent is intentionally not an input.
 */
public final class ConsentQuery {

    private final PolicyReader policyReader;

    public ConsentQuery(PolicyReader policyReader) {
        this.policyReader = Objects.requireNonNull(policyReader, "policyReader");
    }

    public ConsentDecision evaluate(LearnerIdentity identity, MemoryCategory category, Instant at) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(at, "at");

        var policy = policyReader.findForSubject(identity.subjectHash());
        if (policy.isEmpty()) {
            return ConsentDecision.denied(DenialReason.MISSING_POLICY);
        }
        return evaluateCurrent(identity, category, at, policy.orElseThrow());
    }

    private static ConsentDecision evaluateCurrent(
            LearnerIdentity identity,
            MemoryCategory category,
            Instant at,
            ConsentPolicy policy) {
        if (policy.subjectStatus() == SubjectStatus.DISABLED) {
            return ConsentDecision.denied(DenialReason.SUBJECT_DISABLED);
        }
        if (policy.policyStatus() == PolicyStatus.MISSING) {
            return ConsentDecision.denied(DenialReason.MISSING_POLICY);
        }
        if (policy.revision() != identity.consentRevision()) {
            return ConsentDecision.denied(DenialReason.STALE_REVISION);
        }
        if (policy.policyStatus() != PolicyStatus.ACTIVE) {
            return ConsentDecision.denied(DenialReason.POLICY_INACTIVE);
        }
        if (at.isBefore(policy.validFrom())) {
            return ConsentDecision.denied(DenialReason.NOT_YET_VALID);
        }
        if (policy.validUntil() != null && !at.isBefore(policy.validUntil())) {
            return ConsentDecision.denied(DenialReason.TIME_INVALID);
        }
        if (!policy.allowedCategories().contains(category)) {
            return ConsentDecision.denied(DenialReason.CATEGORY_DENIED);
        }
        return new ConsentDecision(true, null);
    }

    @FunctionalInterface
    public interface PolicyReader {
        Optional<ConsentPolicy> findForSubject(String subjectHash);
    }
}
