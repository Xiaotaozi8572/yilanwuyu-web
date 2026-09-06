package com.yilan.memory.application.context;

import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A bounded, already-authorized PostgreSQL retrieval channel. Implementations
 * must not relax learner, status, privacy, time, or source-closure predicates.
 */
public interface RetrievalChannel {

    String name();

    List<RankedMemory> retrieve(AuthorizedMemoryQuery query);

    /** A validated semantic-only omission, never a usable partial context. */
    final class SemanticUnavailableException extends RuntimeException {
        public SemanticUnavailableException(String diagnosticCode) {
            super(Objects.requireNonNull(diagnosticCode, "diagnosticCode"));
        }
    }

    enum UseClass {
        REQUIRED,
        OPTIONAL,
        PROHIBITED
    }

    record AuthorizedMemoryQuery(
            String subjectHash,
            String sessionId,
            Instant asOf,
            Set<MemoryType> allowedTypes,
            Set<MemoryType> requiredTypes,
            String queryText,
            String taskType,
            ResolveMemoryContextUseCase.QueryEmbedding queryEmbedding,
            int candidateLimit) {

        public AuthorizedMemoryQuery {
            if (subjectHash == null || subjectHash.isBlank()) {
                throw new IllegalArgumentException("subjectHash");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId");
            }
            Objects.requireNonNull(asOf, "asOf");
            allowedTypes = Set.copyOf(Objects.requireNonNull(allowedTypes, "allowedTypes"));
            requiredTypes = Set.copyOf(Objects.requireNonNull(requiredTypes, "requiredTypes"));
            if (!allowedTypes.containsAll(requiredTypes)) {
                throw new IllegalArgumentException("requiredTypes must be authorized");
            }
            queryText = Objects.requireNonNullElse(queryText, "");
            taskType = Objects.requireNonNullElse(taskType, "");
            if (candidateLimit <= 0) {
                throw new IllegalArgumentException("candidateLimit");
            }
        }
    }

    /**
     * A projection of one immutable memory version. Safety attributes travel
     * with every channel result so fusion and budgeting can enforce defense in
     * depth rather than trusting a single SQL predicate.
     */
    record RankedMemory(
            UUID memoryId,
            UUID versionId,
            MemoryType memoryType,
            String valueJson,
            BigDecimal confidence,
            BigDecimal stabilityScore,
            Instant validFrom,
            Instant validUntil,
            Instant recordedAt,
            Instant recordedUntil,
            int estimatedTokens,
            boolean sourceClosed,
            PrivacyLevel privacyLevel,
            String status,
            String confirmationStatus,
            List<String> sourceIds,
            String conflictGroup,
            BigDecimal score,
            UseClass useClass) {

        public RankedMemory {
            Objects.requireNonNull(memoryId, "memoryId");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(memoryType, "memoryType");
            if (valueJson == null || valueJson.isBlank()) {
                throw new IllegalArgumentException("valueJson");
            }
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(stabilityScore, "stabilityScore");
            Objects.requireNonNull(validFrom, "validFrom");
            Objects.requireNonNull(recordedAt, "recordedAt");
            if (estimatedTokens < 0) {
                throw new IllegalArgumentException("estimatedTokens");
            }
            Objects.requireNonNull(privacyLevel, "privacyLevel");
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status");
            }
            if (confirmationStatus == null || confirmationStatus.isBlank()) {
                throw new IllegalArgumentException("confirmationStatus");
            }
            sourceIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(useClass, "useClass");
        }

        public RankedMemory withScore(BigDecimal nextScore) {
            return copy(nextScore, useClass, privacyLevel, validFrom, validUntil, sourceClosed,
                    confirmationStatus, conflictGroup, memoryId);
        }

        public RankedMemory withUseClass(UseClass nextUseClass) {
            return copy(score, nextUseClass, privacyLevel, validFrom, validUntil, sourceClosed,
                    confirmationStatus, conflictGroup, memoryId);
        }

        public RankedMemory withPrivacy(PrivacyLevel nextPrivacy) {
            return copy(score, useClass, nextPrivacy, validFrom, validUntil, sourceClosed,
                    confirmationStatus, conflictGroup, memoryId);
        }

        public RankedMemory withValidity(Instant nextValidFrom, Instant nextValidUntil) {
            return copy(score, useClass, privacyLevel, nextValidFrom, nextValidUntil, sourceClosed,
                    confirmationStatus, conflictGroup, memoryId);
        }

        public RankedMemory withSourceClosed(boolean nextSourceClosed) {
            return copy(score, useClass, privacyLevel, validFrom, validUntil, nextSourceClosed,
                    confirmationStatus, conflictGroup, memoryId);
        }

        public RankedMemory withConfirmationStatus(String nextConfirmationStatus) {
            return copy(score, useClass, privacyLevel, validFrom, validUntil, sourceClosed,
                    nextConfirmationStatus, conflictGroup, memoryId);
        }

        public RankedMemory withConflictGroup(String nextConflictGroup) {
            return copy(score, useClass, privacyLevel, validFrom, validUntil, sourceClosed,
                    confirmationStatus, nextConflictGroup, memoryId);
        }

        public RankedMemory withMemoryId(UUID nextMemoryId) {
            return new RankedMemory(nextMemoryId, versionId, memoryType, valueJson, confidence, stabilityScore,
                    validFrom, validUntil, recordedAt, recordedUntil, estimatedTokens, sourceClosed, privacyLevel,
                    status, confirmationStatus, sourceIds, conflictGroup, score, useClass);
        }

        private RankedMemory copy(
                BigDecimal nextScore,
                UseClass nextUseClass,
                PrivacyLevel nextPrivacy,
                Instant nextValidFrom,
                Instant nextValidUntil,
                boolean nextSourceClosed,
                String nextConfirmationStatus,
                String nextConflictGroup,
                UUID nextMemoryId) {
            return new RankedMemory(nextMemoryId, versionId, memoryType, valueJson, confidence, stabilityScore,
                    nextValidFrom, nextValidUntil, recordedAt, recordedUntil, estimatedTokens, nextSourceClosed,
                    nextPrivacy, status, nextConfirmationStatus, sourceIds, nextConflictGroup, nextScore,
                    nextUseClass);
        }
    }
}
