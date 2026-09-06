package com.yilan.memory.domain.governance;

import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Deterministic policy evaluator; mutable thresholds are supplied as typed configuration. */
public final class PromotionRule {

    private final MemoryProperties properties;

    public PromotionRule(MemoryProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public MemoryProperties properties() {
        return properties;
    }

    public GovernanceDecision govern(
            MemoryCandidate candidate,
            List<MemoryCandidate.ValidatedSource> validatedSources,
            Instant authorityNow) {
        Objects.requireNonNull(candidate, "candidate");
        validatedSources = List.copyOf(Objects.requireNonNull(validatedSources, "validatedSources"));
        Objects.requireNonNull(authorityNow, "authorityNow");
        if (validatedSources.size() != candidate.sources().size()
                || !validatedSources.stream().map(MemoryCandidate.ValidatedSource::reference).toList()
                .containsAll(candidate.sources())) {
            throw new IllegalArgumentException("validated sources must close exactly over candidate sources");
        }
        var effectivePrivacy = MemoryCandidate.effectivePrivacyLevel(candidate.privacyLevel(), validatedSources);
        var now = authorityNow;
        var version = properties.ruleSetVersion();

        if (candidate.memoryType() == MemoryType.AVIATION_FACT) {
            return GovernanceDecision.rejected(version, "AVIATION_FACT_FORBIDDEN", now);
        }
        if (candidate.memoryType() == MemoryType.OPTIMIZATION) {
            return GovernanceDecision.pending(version, "OPTIMIZATION_REQUIRES_HUMAN_REVIEW", now);
        }
        if (effectivePrivacy == PrivacyLevel.HIGH) {
            return GovernanceDecision.pending(version, "HIGH_PRIVACY_REQUIRES_HUMAN_REVIEW", now);
        }
        if (candidate.memoryType() == MemoryType.REFLECTION
                && validatedSources.stream().filter(source -> source.sourceKind() == SourceKind.EPISODE).count() < 2) {
            return GovernanceDecision.pending(version, "REFLECTION_REQUIRES_TWO_EPISODES", now);
        }

        var typeRule = properties.ruleFor(candidate.memoryType());
        if (candidate.sources().size() < typeRule.minimumSources()) {
            return GovernanceDecision.pending(version, "INSUFFICIENT_INDEPENDENT_SOURCES", now);
        }
        if (candidate.confidence().compareTo(typeRule.minimumConfidence()) < 0) {
            return GovernanceDecision.pending(version, "CONFIDENCE_BELOW_TYPE_MINIMUM", now);
        }
        if (candidate.memoryType() == MemoryType.PREFERENCE
                && validatedSources.stream().noneMatch(source -> source.sourceKind() == SourceKind.EXPLICIT_DECLARATION)) {
            return GovernanceDecision.pending(version, "PREFERENCE_REQUIRES_EXPLICIT_STATEMENT", now);
        }
        if (candidate.memoryType() == MemoryType.MASTERY
                && validatedSources.stream().noneMatch(source -> source.sourceKind() == SourceKind.SCORED_ASSESSMENT)) {
            return GovernanceDecision.pending(version, "MASTERY_REQUIRES_SCORED_SOURCE", now);
        }
        return GovernanceDecision.accepted(version, now);
    }
}
