package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.governance.GovernCandidateUseCase.MemoryHistoryRepository;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.application.privacy.RetentionPolicy;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.governance.GovernanceDecision;
import com.yilan.memory.domain.governance.MemoryCandidate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL-only immutable history writer. The caller supplies a transaction
 * that encompasses candidate/decision, source validation, history, audit,
 * head projection and learner epoch.
 */
@Repository
public class JdbcMemoryHistoryRepository implements MemoryHistoryRepository {

    private static final String MEMORY_VERSION_ACCEPTED = "MEMORY_VERSION_ACCEPTED";

    private final JdbcClient jdbcClient;
    private final PayloadProtector payloadProtector;
    private final RetentionPolicy retentionPolicy;

    @Autowired
    public JdbcMemoryHistoryRepository(
            JdbcClient jdbcClient, PayloadProtector payloadProtector, RetentionPolicy retentionPolicy) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
        this.retentionPolicy = Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    }

    public JdbcMemoryHistoryRepository(JdbcClient jdbcClient, PayloadProtector payloadProtector) {
        this(jdbcClient, payloadProtector, new RetentionPolicy());
    }

    /** Compatibility constructor for direct STANDARD-only adapter tests. */
    public JdbcMemoryHistoryRepository(JdbcClient jdbcClient) {
        this(jdbcClient, PayloadProtector.disabled(), new RetentionPolicy());
    }

    @Override
    public void appendAccepted(
            MemoryCandidate candidate,
            GovernanceDecision decision,
            List<MemoryCandidate.ValidatedSource> validatedSources,
            MemoryProperties properties,
            Instant authorityNow) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(decision, "decision");
        validatedSources = List.copyOf(Objects.requireNonNull(validatedSources, "validatedSources"));
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(authorityNow, "authorityNow");
        if (validatedSources.isEmpty()) {
            throw new SourceValidationException("accepted candidate requires validated sources");
        }

        var learnerId = learnerId(candidate.subjectHash());
        var assertion = findOrCreateAndLockAssertion(candidate, learnerId, authorityNow);
        if (isTombstoned(learnerId, assertion.id())) {
            throw new SourceValidationException("tombstoned assertion cannot be promoted");
        }
        if (!assertion.memoryType().equals(candidate.memoryType().name())) {
            throw new IllegalArgumentException("assertion key cannot change memory type");
        }
        var currentHead = currentHead(assertion.id());
        long sequence = nextSequence(assertion.id());
        var versionId = UUID.randomUUID();
        var validFrom = validatedSources.stream()
                .map(MemoryCandidate.ValidatedSource::occurredAt)
                .max(Instant::compareTo)
                .orElseThrow(() -> new SourceValidationException("accepted candidate requires source occurrence time"));
        var validUntil = validFrom.plus(retentionPolicy.effectiveFor(
                candidate.memoryType().name(), retentionDaysFor(learnerId, candidate.consentRevision())));

        insertVersion(candidate, learnerId, assertion.id(), versionId, sequence, validFrom, validUntil, authorityNow);
        insertTransition(learnerId, assertion.id(), currentHead, versionId, authorityNow);
        insertSourceLinks(validatedSources, learnerId, versionId, authorityNow);
        long memoryEpoch = incrementEpoch(learnerId, authorityNow);
        upsertHead(assertion.id(), learnerId, versionId, memoryEpoch, authorityNow);
        enqueueAcceptedVersion(candidate, authorityNow);
    }

    private UUID learnerId(String subjectHash) {
        return jdbcClient.sql("SELECT learner_subject_id FROM learner_subject WHERE subject_hash = :subjectHash AND status = 'ACTIVE'")
                .param("subjectHash", subjectHash)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new SourceValidationException("active learner subject is unavailable"));
    }

    private Integer retentionDaysFor(UUID learnerId, long consentRevision) {
        return jdbcClient.sql("""
                        SELECT retention_days FROM consent_policy_version
                        WHERE learner_subject_id = :learnerId AND revision = :revision
                        """).param("learnerId", learnerId).param("revision", consentRevision)
                .query(Integer.class).optional().orElse(null);
    }

    private Assertion findOrCreateAndLockAssertion(MemoryCandidate candidate, UUID learnerId, Instant authorityNow) {
        var assertionId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO memory_assertion (
                            memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at)
                        VALUES (:assertionId, :learnerId, :assertionKey, :memoryType, :createdAt)
                        ON CONFLICT (learner_subject_id, assertion_key) DO NOTHING
                        """)
                .param("assertionId", assertionId)
                .param("learnerId", learnerId)
                .param("assertionKey", candidate.assertionKey())
                .param("memoryType", candidate.memoryType().name())
                .param("createdAt", Timestamp.from(authorityNow))
                .update();
        return jdbcClient.sql("""
                        SELECT memory_assertion_id, memory_type
                        FROM memory_assertion
                        WHERE learner_subject_id = :learnerId AND assertion_key = :assertionKey
                        FOR UPDATE
                        """)
                .param("learnerId", learnerId)
                .param("assertionKey", candidate.assertionKey())
                .query((resultSet, ignored) -> new Assertion(
                        resultSet.getObject("memory_assertion_id", UUID.class), resultSet.getString("memory_type")))
                .single();
    }

    private Head currentHead(UUID assertionId) {
        return jdbcClient.sql("""
                        SELECT memory_version_id
                        FROM memory_head_projection
                        WHERE memory_assertion_id = :assertionId
                        """)
                .param("assertionId", assertionId)
                .query((resultSet, ignored) -> new Head(resultSet.getObject("memory_version_id", UUID.class)))
                .optional()
                .orElse(null);
    }

    private boolean isTombstoned(UUID learnerId, UUID assertionId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM forget_tombstone WHERE learner_subject_id = :learnerId AND memory_assertion_id = :assertionId AND scope = 'ASSERTION')")
                .param("learnerId", learnerId).param("assertionId", assertionId).query(Boolean.class).single();
    }

    private long nextSequence(UUID assertionId) {
        return jdbcClient.sql("SELECT COALESCE(max(version_sequence), 0) + 1 FROM memory_version WHERE memory_assertion_id = :assertionId")
                .param("assertionId", assertionId)
                .query(Long.class)
                .single();
    }

    private void insertVersion(
            MemoryCandidate candidate,
            UUID learnerId,
            UUID assertionId,
            UUID versionId,
            long sequence,
            Instant validFrom,
            Instant validUntil,
            Instant authorityNow) {
        var protectedValue = protectValue(candidate, versionId);
        jdbcClient.sql("""
                        INSERT INTO memory_version (
                            memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                            status, value_json, valid_from, valid_until, recorded_at, recorded_until,
                            confidence, stability_score, privacy_level, consent_revision, created_at,
                            protected_value_ciphertext, protected_value_nonce, protected_value_key_reference,
                            protected_value_algorithm, protected_value_crypto_version)
                        VALUES (:versionId, :assertionId, :learnerId, :sequence, 'ACTIVE',
                                CAST(:valueJson AS jsonb), :validFrom, :validUntil, :recordedAt, null,
                                :confidence, :stabilityScore, :privacyLevel, :consentRevision, :createdAt,
                                :protectedCiphertext, :protectedNonce, :protectedKeyReference,
                                :protectedAlgorithm, :protectedCryptoVersion)
                        """)
                .param("versionId", versionId)
                .param("assertionId", assertionId)
                .param("learnerId", learnerId)
                .param("sequence", sequence)
                .param("valueJson", protectedValue == null ? candidate.valueJson() : null)
                .param("validFrom", Timestamp.from(validFrom))
                .param("validUntil", Timestamp.from(validUntil))
                .param("recordedAt", Timestamp.from(authorityNow))
                .param("confidence", candidate.confidence())
                .param("stabilityScore", candidate.stabilityScore())
                .param("privacyLevel", candidate.privacyLevel().name())
                .param("consentRevision", candidate.consentRevision())
                .param("createdAt", Timestamp.from(authorityNow))
                .param("protectedCiphertext", protectedValue == null ? null : protectedValue.ciphertextAndTag())
                .param("protectedNonce", protectedValue == null ? null : protectedValue.nonce())
                .param("protectedKeyReference", protectedValue == null ? null : protectedValue.keyReference())
                .param("protectedAlgorithm", protectedValue == null ? null : EncryptedPayload.ALGORITHM)
                .param("protectedCryptoVersion", protectedValue == null ? null : EncryptedPayload.CRYPTO_VERSION)
                .update();
    }

    private EncryptedPayload protectValue(MemoryCandidate candidate, UUID versionId) {
        if (candidate.privacyLevel() == com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel.STANDARD) {
            return null;
        }
        return payloadProtector.encrypt(new PayloadProtector.PayloadBinding(
                candidate.subjectHash(), versionId, MemoryCandidate.VALUE_SCHEMA_VERSION, "memory_version",
                KeyPurpose.MEMORY_VERSION_VALUE), candidate.valueJson().getBytes(StandardCharsets.UTF_8));
    }

    private void insertTransition(
            UUID learnerId,
            UUID assertionId,
            Head currentHead,
            UUID versionId,
            Instant authorityNow) {
        var transitionType = currentHead == null ? "ACTIVATE" : "SUPERSEDE";
        jdbcClient.sql("""
                        INSERT INTO memory_transition (
                            memory_transition_id, memory_assertion_id, learner_subject_id,
                            from_memory_version_id, to_memory_version_id, transition_type,
                            reason_code, transitioned_at)
                        VALUES (:transitionId, :assertionId, :learnerId, :fromVersionId, :toVersionId,
                                :transitionType, 'GOVERNANCE_ACCEPTED', :transitionedAt)
                        """)
                .param("transitionId", UUID.randomUUID())
                .param("assertionId", assertionId)
                .param("learnerId", learnerId)
                .param("fromVersionId", currentHead == null ? null : currentHead.versionId())
                .param("toVersionId", versionId)
                .param("transitionType", transitionType)
                .param("transitionedAt", Timestamp.from(authorityNow))
                .update();
    }

    private void insertSourceLinks(
            List<MemoryCandidate.ValidatedSource> validatedSources,
            UUID learnerId,
            UUID versionId,
            Instant authorityNow) {
        for (var validatedSource : validatedSources) {
            var source = validatedSource.reference();
            jdbcClient.sql("""
                            INSERT INTO memory_source_link (
                                memory_source_link_id, memory_version_id, learner_subject_id,
                                source_event_id, source_schema_version, source_kind, created_at)
                            VALUES (:linkId, :versionId, :learnerId, :eventId, :schemaVersion,
                                    'INTERACTION_EVENT', :createdAt)
                            """)
                    .param("linkId", UUID.randomUUID())
                    .param("versionId", versionId)
                    .param("learnerId", learnerId)
                    .param("eventId", source.eventId())
                    .param("schemaVersion", source.schemaVersion())
                    .param("createdAt", Timestamp.from(authorityNow))
                    .update();
        }
    }

    private long incrementEpoch(UUID learnerId, Instant at) {
        jdbcClient.sql("""
                        INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at)
                        VALUES (:learnerId, 0, 0, :updatedAt)
                        ON CONFLICT (learner_subject_id) DO NOTHING
                        """)
                .param("learnerId", learnerId)
                .param("updatedAt", Timestamp.from(at))
                .update();
        return jdbcClient.sql("""
                        UPDATE learner_epoch
                        SET memory_epoch = memory_epoch + 1, updated_at = :updatedAt
                        WHERE learner_subject_id = :learnerId
                        RETURNING memory_epoch
                        """)
                .param("updatedAt", Timestamp.from(at))
                .param("learnerId", learnerId)
                .query(Long.class)
                .single();
    }

    private void upsertHead(UUID assertionId, UUID learnerId, UUID versionId, long memoryEpoch, Instant at) {
        jdbcClient.sql("""
                        INSERT INTO memory_head_projection (
                            memory_assertion_id, learner_subject_id, memory_version_id, status,
                            memory_epoch, updated_at)
                        VALUES (:assertionId, :learnerId, :versionId, 'ACTIVE', :memoryEpoch, :updatedAt)
                        ON CONFLICT (memory_assertion_id) DO UPDATE
                        SET memory_version_id = EXCLUDED.memory_version_id,
                            status = EXCLUDED.status,
                            memory_epoch = EXCLUDED.memory_epoch,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("assertionId", assertionId)
                .param("learnerId", learnerId)
                .param("versionId", versionId)
                .param("memoryEpoch", memoryEpoch)
                .param("updatedAt", Timestamp.from(at))
                .update();
    }

    /**
     * This runs in the transaction supplied by {@link GovernCandidateUseCase}.
     * The payload intentionally carries only authority identifiers so the
     * downstream relay can re-fetch state rather than treating this row as a
     * memory value or a worker-granted authority write.
     */
    private void enqueueAcceptedVersion(MemoryCandidate candidate, Instant authorityNow) {
        var source = candidate.sources().getFirst();
        var payload = "{\"event_schema_version\":\"" + source.schemaVersion()
                + "\",\"memory_candidate_id\":\"" + candidate.candidateId() + "\"}";
        jdbcClient.sql("""
                        INSERT INTO transactional_outbox (outbox_id, aggregate_id, event_type, payload, created_at)
                        VALUES (:outboxId, :aggregateId, :eventType, CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("outboxId", UUID.randomUUID())
                .param("aggregateId", source.eventId())
                .param("eventType", MEMORY_VERSION_ACCEPTED)
                .param("payload", payload)
                .param("createdAt", Timestamp.from(authorityNow))
                .update();
    }

    public static final class SourceValidationException extends RuntimeException {
        public SourceValidationException(String message) {
            super(message);
        }
    }

    private record Assertion(UUID id, String memoryType) {
    }

    private record Head(UUID versionId) {
    }
}
