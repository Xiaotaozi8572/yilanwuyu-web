package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.governance.GovernCandidateUseCase.CandidateAppendResult;
import com.yilan.memory.application.governance.GovernCandidateUseCase.CandidateRepository;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.GovernanceDecision;
import com.yilan.memory.domain.governance.GovernanceDecision.Decision;
import com.yilan.memory.domain.governance.MemoryCandidate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL candidate boundary. It closes every source over the authenticated
 * learner event ledger and turns candidate-id retries into immutable reads.
 */
@Repository
public class JdbcCandidateRepository implements CandidateRepository {

    private static final Pattern REASON_CODE = Pattern.compile("\\\"([A-Z0-9_]{1,96})\\\"");

    private final JdbcClient jdbcClient;
    private final PayloadProtector payloadProtector;

    @Autowired
    public JdbcCandidateRepository(JdbcClient jdbcClient, PayloadProtector payloadProtector) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
    }

    /** Compatibility constructor for direct STANDARD-only adapter tests. */
    public JdbcCandidateRepository(JdbcClient jdbcClient) {
        this(jdbcClient, PayloadProtector.disabled());
    }

    @Override
    public Optional<GovernanceDecision> findExistingDecision(MemoryCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return existing(candidate).map(StoredCandidate::decision);
    }

    @Override
    public List<MemoryCandidate.ValidatedSource> validateSources(MemoryCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        var validated = new ArrayList<MemoryCandidate.ValidatedSource>(candidate.sources().size());
        for (var source : candidate.sources()) {
            var evidence = jdbcClient.sql("""
                            SELECT event.source_kind, event.occurred_at, event.privacy_level
                            FROM interaction_event event
                            JOIN learner_subject subject ON subject.learner_subject_id = event.learner_subject_id
                            WHERE event.event_id = :eventId
                              AND event.schema_version = :schemaVersion
                              AND subject.subject_hash = :subjectHash
                              AND subject.status = 'ACTIVE'
                            """)
                    .param("eventId", source.eventId())
                    .param("schemaVersion", source.schemaVersion())
                    .param("subjectHash", candidate.subjectHash())
                    .query((resultSet, ignored) -> new MemoryCandidate.ValidatedSource(
                            source,
                            sourceKind(resultSet.getString("source_kind")),
                            resultSet.getTimestamp("occurred_at").toInstant(),
                            authorityPrivacyLevel(resultSet.getString("privacy_level"))))
                    .optional()
                    .orElseThrow(() -> new SourceValidationException(
                            "candidate source is missing or belongs to another learner"));
            validated.add(evidence);
        }
        return List.copyOf(validated);
    }

    @Override
    public CandidateAppendResult appendIfAbsent(
            MemoryCandidate candidate,
            GovernanceDecision decision,
            Instant authorityNow) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(authorityNow, "authorityNow");
        var primarySource = candidate.sources().getFirst();
        var protectedPayload = protectCandidate(candidate);
        var candidateRows = jdbcClient.sql("""
                        INSERT INTO memory_candidate (
                            candidate_id, learner_subject_id, event_id, event_schema_version, memory_type,
                            status, privacy_level, candidate_ciphertext, candidate_digest, source_count, created_at,
                            candidate_key_reference, candidate_nonce, candidate_algorithm, candidate_crypto_version)
                        SELECT :candidateId, subject.learner_subject_id, event.event_id, event.schema_version,
                               :memoryType, :status, :privacyLevel, :candidateCiphertext, :candidateDigest,
                               :sourceCount, :createdAt, :candidateKeyReference, :candidateNonce,
                               :candidateAlgorithm, :candidateCryptoVersion
                        FROM interaction_event event
                        JOIN learner_subject subject ON subject.learner_subject_id = event.learner_subject_id
                        WHERE event.event_id = :eventId
                          AND event.schema_version = :eventSchemaVersion
                          AND subject.subject_hash = :subjectHash
                          AND subject.status = 'ACTIVE'
                        ON CONFLICT DO NOTHING
                        """)
                .param("candidateId", candidate.candidateId())
                .param("memoryType", candidate.memoryType().name())
                .param("status", candidateStatus(decision.decision()))
                .param("privacyLevel", candidate.privacyLevel().name())
                .param("candidateCiphertext", protectedPayload == null
                        ? candidate.candidateCiphertext() : protectedPayload.serialize())
                .param("candidateDigest", digest(protectedPayload == null
                        ? candidate.candidateCiphertext() : protectedPayload.serialize()))
                .param("sourceCount", candidate.sources().size())
                .param("createdAt", Timestamp.from(authorityNow))
                .param("candidateKeyReference", protectedPayload == null ? null : protectedPayload.keyReference())
                .param("candidateNonce", protectedPayload == null ? null : protectedPayload.nonce())
                .param("candidateAlgorithm", protectedPayload == null ? null : EncryptedPayload.ALGORITHM)
                .param("candidateCryptoVersion", protectedPayload == null ? null : EncryptedPayload.CRYPTO_VERSION)
                .param("eventId", primarySource.eventId())
                .param("eventSchemaVersion", primarySource.schemaVersion())
                .param("subjectHash", candidate.subjectHash())
                .update();
        if (candidateRows == 0) {
            return new CandidateAppendResult(false, existing(candidate)
                    .orElseThrow(() -> new SourceValidationException(
                            "candidate source is missing or belongs to another learner"))
                    .decision());
        }
        if (candidateRows != 1) {
            throw new SourceValidationException("candidate source is missing or belongs to another learner");
        }

        int decisionRows = jdbcClient.sql("""
                        INSERT INTO governance_decision (
                            governance_decision_id, candidate_id, learner_subject_id, decision,
                            rule_set_version, reason_codes, decided_at)
                        SELECT :decisionId, candidate.candidate_id, candidate.learner_subject_id, :decision,
                               :ruleSetVersion, CAST(:reasonCodes AS jsonb), :decidedAt
                        FROM memory_candidate candidate
                        WHERE candidate.candidate_id = :candidateId
                        """)
                .param("decisionId", UUID.randomUUID())
                .param("candidateId", candidate.candidateId())
                .param("decision", decision.decision().name())
                .param("ruleSetVersion", decision.ruleSetVersion())
                .param("reasonCodes", jsonArray(decision.reasonCodes()))
                .param("decidedAt", Timestamp.from(authorityNow))
                .update();
        if (decisionRows != 1) {
            throw new IllegalStateException("candidate decision write did not affect exactly one row");
        }

        int auditRows = jdbcClient.sql("""
                        INSERT INTO memory_audit_event (
                            memory_audit_event_id, learner_subject_id, audit_type, actor_type,
                            correlation_id, redacted_metadata, created_at)
                        SELECT :auditId, candidate.learner_subject_id, :auditType, 'SYSTEM',
                               :correlationId, CAST(:metadata AS jsonb), :createdAt
                        FROM memory_candidate candidate
                        WHERE candidate.candidate_id = :candidateId
                        """)
                .param("auditId", UUID.randomUUID())
                .param("auditType", "MEMORY_GOVERNANCE_" + decision.decision().name())
                .param("correlationId", candidate.candidateId().toString())
                .param("metadata", redactedAuditMetadataJson(candidate, decision))
                .param("createdAt", Timestamp.from(authorityNow))
                .param("candidateId", candidate.candidateId())
                .update();
        if (auditRows != 1) {
            throw new IllegalStateException("candidate audit write did not affect exactly one row");
        }
        return new CandidateAppendResult(true, decision);
    }

    private static String redactedAuditMetadataJson(
            MemoryCandidate candidate,
            GovernanceDecision decision) {
        return "{\"candidate_id\":\"" + candidate.candidateId()
                + "\",\"memory_type\":\"" + candidate.memoryType()
                + "\",\"rule_set_version\":\"" + decision.ruleSetVersion()
                + "\",\"outcome\":\"" + decision.decision()
                + "\",\"reason_codes\":" + jsonArray(decision.reasonCodes()) + "}";
    }

    private EncryptedPayload protectCandidate(MemoryCandidate candidate) {
        if (!requiresProtection(candidate.privacyLevel())) {
            return null;
        }
        return payloadProtector.encrypt(new PayloadProtector.PayloadBinding(
                candidate.subjectHash(), candidate.candidateId(), "candidate/v1", "memory_candidate",
                KeyPurpose.MEMORY_CANDIDATE), candidate.candidateCiphertext());
    }

    private static boolean requiresProtection(PrivacyLevel privacyLevel) {
        return privacyLevel == PrivacyLevel.SENSITIVE || privacyLevel == PrivacyLevel.HIGH;
    }

    private static String digest(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private Optional<StoredCandidate> existing(MemoryCandidate candidate) {
        var existing = jdbcClient.sql("""
                        SELECT subject.subject_hash, decision.decision, decision.rule_set_version,
                               decision.reason_codes::text AS reason_codes, decision.decided_at
                        FROM memory_candidate candidate
                        JOIN learner_subject subject ON subject.learner_subject_id = candidate.learner_subject_id
                        LEFT JOIN governance_decision decision ON decision.candidate_id = candidate.candidate_id
                        WHERE candidate.candidate_id = :candidateId
                        """)
                .param("candidateId", candidate.candidateId())
                .query((resultSet, ignored) -> new ExistingRow(
                        resultSet.getString("subject_hash"),
                        resultSet.getString("decision"),
                        resultSet.getString("rule_set_version"),
                        resultSet.getString("reason_codes"),
                        resultSet.getTimestamp("decided_at")))
                .optional();
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        var row = existing.orElseThrow();
        if (!candidate.subjectHash().equals(row.subjectHash())) {
            throw new CandidateOwnershipException("candidate id belongs to another learner");
        }
        if (row.decision() == null || row.ruleSetVersion() == null
                || row.reasonCodes() == null || row.decidedAt() == null) {
            throw new CandidateStateException("candidate retry has no immutable governance decision");
        }
        return Optional.of(new StoredCandidate(new GovernanceDecision(
                Decision.valueOf(row.decision()),
                row.ruleSetVersion(),
                parseReasonCodes(row.reasonCodes()),
                row.decidedAt().toInstant())));
    }

    private static SourceKind sourceKind(String value) {
        try {
            return SourceKind.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new SourceValidationException("source kind is not a valid authority value");
        }
    }

    private static PrivacyLevel authorityPrivacyLevel(String value) {
        try {
            return PrivacyLevel.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new SourceValidationException("source privacy level is not a valid authority value");
        }
    }

    private static String candidateStatus(Decision decision) {
        return switch (decision) {
            case ACCEPTED -> "GOVERNED";
            case REJECTED -> "REJECTED";
            case PENDING -> "PENDING";
        };
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<String> parseReasonCodes(String value) {
        var matcher = REASON_CODE.matcher(value);
        var codes = new ArrayList<String>();
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        if (codes.isEmpty()) {
            throw new CandidateStateException("stored governance decision has invalid reason codes");
        }
        return List.copyOf(codes);
    }

    public static final class SourceValidationException extends RuntimeException {
        public SourceValidationException(String message) {
            super(message);
        }
    }

    public static final class CandidateOwnershipException extends RuntimeException {
        public CandidateOwnershipException(String message) {
            super(message);
        }
    }

    public static final class CandidateStateException extends RuntimeException {
        public CandidateStateException(String message) {
            super(message);
        }
    }

    private record ExistingRow(
            String subjectHash,
            String decision,
            String ruleSetVersion,
            String reasonCodes,
            Timestamp decidedAt) {
    }

    private record StoredCandidate(GovernanceDecision decision) {
    }
}
