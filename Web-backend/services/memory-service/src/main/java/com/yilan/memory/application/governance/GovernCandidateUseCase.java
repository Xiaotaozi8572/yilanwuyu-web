package com.yilan.memory.application.governance;

import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.governance.GovernanceDecision;
import com.yilan.memory.domain.governance.GovernanceDecision.Decision;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.governance.PromotionRule;
import com.yilan.memory.domain.identity.LearnerIdentity;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single governance write boundary. A TransactionOperations adapter makes
 * candidate, decision, immutable history, projection and epoch one transaction
 * even when invoked outside an HTTP/gRPC component.
 */
public final class GovernCandidateUseCase {

    private final PromotionRule promotionRule;
    private final CandidateRepository candidates;
    private final MemoryHistoryRepository history;
    private final ConsentQuery consentQuery;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final MemoryProperties properties;

    public GovernCandidateUseCase(
            PromotionRule promotionRule,
            CandidateRepository candidates,
            MemoryHistoryRepository history,
            ConsentQuery.PolicyReader policyReader,
            TransactionOperations transactions,
            Clock clock) {
        this.promotionRule = Objects.requireNonNull(promotionRule, "promotionRule");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.history = Objects.requireNonNull(history, "history");
        this.consentQuery = new ConsentQuery(Objects.requireNonNull(policyReader, "policyReader"));
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = promotionRule.properties();
    }

    public GovernanceDecision govern(MemoryCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return Objects.requireNonNull(transactions.execute(ignored -> governInTransaction(candidate)));
    }

    /**
     * Records a source-bound rejection for a malformed or overreaching worker
     * response. It intentionally never calls the active history writer.
     */
    public GovernanceDecision rejectWorkerBoundary(MemoryCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return Objects.requireNonNull(transactions.execute(ignored -> {
            var existing = candidates.findExistingDecision(candidate);
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            var validatedSources = candidates.validateSources(candidate);
            var effectiveCandidate = candidate.withEffectivePrivacy(validatedSources);
            var decision = GovernanceDecision.rejected(
                    properties.ruleSetVersion(), "WORKER_BOUNDARY_VIOLATION", Instant.now(clock));
            var append = candidates.appendIfAbsent(effectiveCandidate, decision, Instant.now(clock));
            return append.inserted() ? decision : append.decision();
        }));
    }

    private GovernanceDecision governInTransaction(MemoryCandidate candidate) {
        var existing = candidates.findExistingDecision(candidate);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        var validatedSources = candidates.validateSources(candidate);
        var effectiveCandidate = candidate.withEffectivePrivacy(validatedSources);
        var authorityNow = Instant.now(clock);
        var decision = promotionRule.govern(effectiveCandidate, validatedSources, authorityNow);
        if (decision.decision() != Decision.REJECTED) {
            decision = applyCurrentConsent(effectiveCandidate, decision, authorityNow);
        }
        var append = candidates.appendIfAbsent(effectiveCandidate, decision, authorityNow);
        if (!append.inserted()) {
            return append.decision();
        }
        if (decision.decision() == Decision.ACCEPTED) {
            history.appendAccepted(effectiveCandidate, decision, validatedSources, properties, authorityNow);
        }
        return decision;
    }

    private GovernanceDecision applyCurrentConsent(
            MemoryCandidate candidate,
            GovernanceDecision decision,
            Instant authorityNow) {
        Optional<MemoryCategory> category = consentCategory(candidate.memoryType());
        if (category.isEmpty()) {
            return decision;
        }
        var identity = new LearnerIdentity(candidate.subjectHash(), "governance", candidate.consentRevision());
        var consent = consentQuery.evaluate(identity, category.orElseThrow(), authorityNow);
        if (consent.allowed()) {
            return decision;
        }
        return GovernanceDecision.rejected(
                decision.ruleSetVersion(), "CONSENT_" + consent.denialReason().name(), authorityNow);
    }

    private static Optional<MemoryCategory> consentCategory(MemoryType type) {
        return switch (type) {
            case PREFERENCE -> Optional.of(MemoryCategory.PREFERENCE);
            case MASTERY -> Optional.of(MemoryCategory.MASTERY);
            case MISCONCEPTION -> Optional.of(MemoryCategory.MISCONCEPTION);
            case REFLECTION -> Optional.of(MemoryCategory.REFLECTION);
            case AVIATION_FACT, OPTIMIZATION -> Optional.empty();
        };
    }

    public interface CandidateRepository {
        Optional<GovernanceDecision> findExistingDecision(MemoryCandidate candidate);

        List<MemoryCandidate.ValidatedSource> validateSources(MemoryCandidate candidate);

        CandidateAppendResult appendIfAbsent(
                MemoryCandidate candidate,
                GovernanceDecision decision,
                Instant authorityNow);
    }

    public interface MemoryHistoryRepository {
        void appendAccepted(
                MemoryCandidate candidate,
                GovernanceDecision decision,
                List<MemoryCandidate.ValidatedSource> validatedSources,
                MemoryProperties properties,
                Instant authorityNow);
    }

    public record CandidateAppendResult(boolean inserted, GovernanceDecision decision) {
        public CandidateAppendResult {
            Objects.requireNonNull(decision, "decision");
        }
    }
}
