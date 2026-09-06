package com.yilan.memory.adapter.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yilan.memory.application.management.MemoryManagementRepository;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.InteractionPayloadKeyEnvelopeStore;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.application.privacy.RetentionPolicy;
import com.yilan.memory.domain.governance.MemoryCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL authority adapter for subject-bound, append-only user actions. */
@Repository
public class JdbcMemoryManagementRepository implements MemoryManagementRepository {

    private static final int PAGE_SIZE = 50;
    private static final String PROJECTION_PURGE = "MEMORY_PROJECTION_PURGE";

    private final JdbcClient jdbcClient;
    private final PayloadProtector payloadProtector;
    private final RetentionPolicy retentionPolicy;
    private final InteractionPayloadKeyEnvelopeStore sourceEnvelopes;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public JdbcMemoryManagementRepository(
            JdbcClient jdbcClient,
            PayloadProtector payloadProtector,
            RetentionPolicy retentionPolicy,
            InteractionPayloadKeyEnvelopeStore sourceEnvelopes) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
        this.retentionPolicy = Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        this.sourceEnvelopes = sourceEnvelopes;
    }

    public JdbcMemoryManagementRepository(JdbcClient jdbcClient, PayloadProtector payloadProtector) {
        this(jdbcClient, payloadProtector, new RetentionPolicy(), null);
    }

    /** Compatibility constructor for direct STANDARD-only adapter tests. */
    public JdbcMemoryManagementRepository(JdbcClient jdbcClient) {
        this(jdbcClient, PayloadProtector.disabled(), new RetentionPolicy(), null);
    }

    @Override
    public MemoryPage list(ListQuery query) {
        Objects.requireNonNull(query, "query");
        var cursor = parseCursor(query.cursor());
        var sql = new StringBuilder("""
                SELECT subject.subject_hash, head.memory_assertion_id, version.memory_version_id, version.version_sequence,
                       assertion.memory_type, head.status, version.value_json::text AS value_json,
                       version.protected_value_ciphertext, version.protected_value_nonce,
                       version.protected_value_key_reference, version.protected_value_algorithm,
                       version.protected_value_crypto_version, version.confidence, version.privacy_level, version.valid_from,
                       version.valid_until, head.updated_at,
                       COALESCE((SELECT transition.reason_code FROM memory_transition transition
                                 WHERE transition.memory_assertion_id = head.memory_assertion_id
                                   AND transition.learner_subject_id = subject.learner_subject_id
                                 ORDER BY transition.transitioned_at DESC LIMIT 1), '') AS latest_reason
                FROM learner_subject subject
                JOIN memory_head_projection head ON head.learner_subject_id = subject.learner_subject_id
                JOIN memory_assertion assertion ON assertion.memory_assertion_id = head.memory_assertion_id
                    AND assertion.learner_subject_id = subject.learner_subject_id
                JOIN memory_version version ON version.memory_version_id = head.memory_version_id
                    AND version.memory_assertion_id = head.memory_assertion_id
                    AND version.learner_subject_id = subject.learner_subject_id
                WHERE subject.subject_hash = :subjectHash
                  AND subject.status = 'ACTIVE'
                  AND head.status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM forget_tombstone tombstone
                                  WHERE tombstone.learner_subject_id = subject.learner_subject_id
                                    AND tombstone.memory_assertion_id = head.memory_assertion_id
                                    AND tombstone.scope = 'ASSERTION')
                """);
        if (query.type() != null && !query.type().isBlank()) {
            sql.append(" AND assertion.memory_type = :memoryType");
        }
        if (query.status() != null && !query.status().isBlank()) {
            sql.append(" AND head.status = :memoryStatus");
        }
        if (query.effectiveAfter() != null) {
            sql.append(" AND version.valid_from >= :effectiveAfter");
        }
        if (cursor != null) {
            sql.append(" AND (head.updated_at, head.memory_assertion_id) < (:cursorUpdatedAt, :cursorAssertionId)");
        }
        sql.append(" ORDER BY head.updated_at DESC, head.memory_assertion_id DESC LIMIT :limit");
        var statement = jdbcClient.sql(sql.toString()).param("subjectHash", query.subjectHash());
        if (query.type() != null && !query.type().isBlank()) {
            statement = statement.param("memoryType", query.type());
        }
        if (query.status() != null && !query.status().isBlank()) {
            statement = statement.param("memoryStatus", query.status());
        }
        if (query.effectiveAfter() != null) {
            statement = statement.param("effectiveAfter", Timestamp.from(query.effectiveAfter()));
        }
        if (cursor != null) {
            statement = statement.param("cursorUpdatedAt", Timestamp.from(cursor.updatedAt()))
                    .param("cursorAssertionId", cursor.memoryAssertionId());
        }
        var rows = statement.param("limit", PAGE_SIZE + 1).query(this::mapListRow).list();
        var hasMore = rows.size() > PAGE_SIZE;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, PAGE_SIZE));
        }
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            var last = rows.getLast();
            nextCursor = encodeCursor(last.updatedAt(), last.summary().id());
        }
        return new MemoryPage(rows.stream().map(ListRow::summary).toList(), nextCursor);
    }

    @Override
    public Optional<MemoryDetail> findDetail(String subjectHash, UUID memoryAssertionId) {
        Objects.requireNonNull(subjectHash, "subjectHash");
        Objects.requireNonNull(memoryAssertionId, "memoryAssertionId");
        return findDetail(subjectHash, memoryAssertionId, null, true);
    }

    @Override
    @Transactional
    public ActionResult confirm(Mutation mutation) {
        var subject = lockSubject(mutation.subjectHash());
        var requestDigest = digest("CONFIRM|" + mutation.memoryAssertionId() + '|' + mutation.expectedVersion());
        return replayOrRun(subject, mutation, "CONFIRM", requestDigest, () -> {
            var head = lockHead(subject.id(), mutation.memoryAssertionId());
            var policy = requireActivePolicyForCategory(subject.id(), head.memoryType());
            requireExpectedVersion(head, mutation.expectedVersion());
            if ("STALE".equals(head.status())) {
                requireStalePreference(head);
                var at = Instant.now();
                var renewedVersionId = UUID.randomUUID();
                insertReconfirmedVersion(subject.id(), subject.subjectHash(), head, renewedVersionId, policy, at);
                var epoch = incrementEpoch(subject.id());
                insertTransition(subject.id(), head.assertionId(), head.versionId(), renewedVersionId,
                        "ACTIVATE", "USER_RECONFIRMED");
                updateHead(head.assertionId(), subject.id(), renewedVersionId, "ACTIVE", epoch);
                enqueuePurge(subject.id(), head.assertionId(), epoch);
                return new ActionOutcome(renewedVersionId, "ACTIVE");
            }
            requireActiveHead(head);
            var epoch = incrementEpoch(subject.id());
            insertTransition(subject.id(), head.assertionId(), null, head.versionId(), "ACTIVATE", "USER_CONFIRMED");
            updateHead(head.assertionId(), subject.id(), head.versionId(), "ACTIVE", epoch);
            enqueuePurge(subject.id(), head.assertionId(), epoch);
            return new ActionOutcome(head.versionId(), "ACTIVE");
        });
    }

    @Override
    @Transactional
    public ActionResult correct(Correction mutation) {
        var subject = lockSubject(mutation.subjectHash());
        var correctedJson = serializeValue(mutation.correctedValue());
        var requestDigest = digest("CORRECT|" + mutation.memoryAssertionId() + '|' + mutation.expectedVersion()
                + '|' + correctedJson + '|' + Objects.toString(mutation.reason(), ""));
        return replayOrRun(subject, mutation, "CORRECT", requestDigest, () -> {
            var head = lockHead(subject.id(), mutation.memoryAssertionId());
            requireActiveHead(head);
            var policyRevision = requireActivePolicyForCategory(subject.id(), head.memoryType()).revision();
            requireExpectedVersion(head, mutation.expectedVersion());
            var at = Instant.now();
            var correctionEventId = insertOpaqueCorrectionEvent(
                    subject.id(), subject.subjectHash(), head.privacyClass(), policyRevision, requestDigest, at);
            var correctedVersionId = UUID.randomUUID();
            insertCorrectedVersion(subject.id(), subject.subjectHash(), head, correctedVersionId, correctedJson, policyRevision, at);
            insertCorrectionSourceLink(subject.id(), correctedVersionId, correctionEventId, at);
            var epoch = incrementEpoch(subject.id());
            insertTransition(subject.id(), head.assertionId(), head.versionId(), correctedVersionId, "SUPERSEDE", "USER_CORRECTED");
            updateHead(head.assertionId(), subject.id(), correctedVersionId, "ACTIVE", epoch);
            enqueuePurge(subject.id(), head.assertionId(), epoch);
            return new ActionOutcome(correctedVersionId, "ACTIVE");
        });
    }

    @Override
    @Transactional
    public ActionResult disable(Mutation mutation) {
        var subject = lockSubject(mutation.subjectHash());
        var requestDigest = digest("DISABLE|" + mutation.memoryAssertionId() + '|' + mutation.expectedVersion());
        return replayOrRun(subject, mutation, "DISABLE", requestDigest, () -> {
            var head = lockHead(subject.id(), mutation.memoryAssertionId());
            requireExpectedVersion(head, mutation.expectedVersion());
            var epoch = incrementEpoch(subject.id());
            insertTransition(subject.id(), head.assertionId(), head.versionId(), head.versionId(), "ARCHIVE", "USER_DISABLED");
            updateHead(head.assertionId(), subject.id(), head.versionId(), "DISABLED", epoch);
            enqueuePurge(subject.id(), head.assertionId(), epoch);
            return new ActionOutcome(head.versionId(), "DISABLED");
        });
    }

    private ActionResult replayOrRun(
            Subject subject,
            Mutation mutation,
            String action,
            String requestDigest,
            VersionAction actionWork) {
        var keyDigest = digest(mutation.idempotencyKey());
        var existing = findReceipt(subject.id(), keyDigest);
        if (existing.isPresent()) {
            var receipt = existing.orElseThrow();
            if (!receipt.requestDigest().equals(requestDigest)) {
                throw new IdempotencyConflictException();
            }
            return receiptResult(subject, receipt);
        }
        final ActionOutcome outcome;
        try {
            outcome = actionWork.run();
            insertReceipt(subject.id(), mutation.memoryAssertionId(), action, keyDigest, requestDigest,
                    outcome.versionId(), outcome.status());
        } catch (DataIntegrityViolationException exception) {
            var raced = findReceipt(subject.id(), keyDigest);
            if (raced.isPresent() && raced.orElseThrow().requestDigest().equals(requestDigest)) {
                return receiptResult(subject, raced.orElseThrow());
            }
            throw new IdempotencyConflictException();
        }
        return detailResult(subject.subjectHash(), mutation.memoryAssertionId());
    }

    private ActionResult replayOrRun(
            Subject subject,
            Correction mutation,
            String action,
            String requestDigest,
            VersionAction actionWork) {
        return replayOrRun(subject, new Mutation(
                mutation.subjectHash(), mutation.memoryAssertionId(), mutation.expectedVersion(), mutation.idempotencyKey()),
                action, requestDigest, actionWork);
    }

    private ActionResult detailResult(String subjectHash, UUID assertionId) {
        return new ActionResult(findDetail(subjectHash, assertionId, null, false)
                .orElseThrow(MemoryNotFoundException::new));
    }

    private ActionResult receiptResult(Subject subject, Receipt receipt) {
        return new ActionResult(findReceiptDetail(subject.subjectHash(), receipt)
                .orElseThrow(MemoryNotFoundException::new));
    }

    private Optional<MemoryDetail> findReceiptDetail(String subjectHash, Receipt receipt) {
        return jdbcClient.sql("""
                        SELECT subject.subject_hash, assertion.memory_assertion_id, version.memory_version_id,
                               version.version_sequence, assertion.memory_type, version.value_json::text AS value_json,
                               version.protected_value_ciphertext, version.protected_value_nonce,
                               version.protected_value_key_reference, version.protected_value_algorithm,
                               version.protected_value_crypto_version, version.confidence,
                               version.valid_from, version.valid_until, version.privacy_level,
                               EXISTS (SELECT 1 FROM memory_source_link source
                                       WHERE source.memory_version_id = version.memory_version_id
                                         AND source.learner_subject_id = subject.learner_subject_id) AS has_source
                        FROM learner_subject subject
                        JOIN memory_assertion assertion ON assertion.learner_subject_id = subject.learner_subject_id
                        JOIN memory_version version ON version.memory_version_id = :versionId
                            AND version.memory_assertion_id = assertion.memory_assertion_id
                            AND version.learner_subject_id = subject.learner_subject_id
                        WHERE subject.subject_hash = :subjectHash
                          AND assertion.memory_assertion_id = :assertionId
                          AND subject.status = 'ACTIVE'
                          AND NOT EXISTS (SELECT 1 FROM forget_tombstone tombstone
                                          WHERE tombstone.learner_subject_id = subject.learner_subject_id
                                            AND tombstone.memory_assertion_id = assertion.memory_assertion_id
                                            AND tombstone.scope = 'ASSERTION')
                        """)
                .param("subjectHash", subjectHash)
                .param("assertionId", receipt.assertionId())
                .param("versionId", receipt.resultVersionId())
                .query((resultSet, ignored) -> new MemoryDetail(
                        resultSet.getObject("memory_assertion_id", UUID.class),
                        Long.toString(resultSet.getLong("version_sequence")),
                        resultSet.getString("memory_type"),
                        receipt.resultStatus(),
                        confirmationStatusForAction(receipt.action()),
                        parseValue(readValueJson(resultSet)),
                        confidenceBand(resultSet.getBigDecimal("confidence")),
                        resultSet.getTimestamp("valid_from").toInstant(),
                        timestamp(resultSet, "valid_until"),
                        "OPTIONAL",
                        resultSet.getString("privacy_level"),
                        List.of(resultSet.getBoolean("has_source") ? "linked-source" : "recorded-memory"),
                        reasonForAction(receipt.action()),
                        List.of()))
                .optional();
    }

    private Optional<MemoryDetail> findDetail(String subjectHash, UUID assertionId, UUID versionId, boolean activeOnly) {
        var versionPredicate = versionId == null ? "head.memory_version_id" : ":versionId";
        return jdbcClient.sql("""
                        SELECT subject.subject_hash, head.memory_assertion_id, version.memory_version_id,
                               version.version_sequence, assertion.memory_type, head.status,
                               version.value_json::text AS value_json, version.protected_value_ciphertext,
                               version.protected_value_nonce, version.protected_value_key_reference,
                               version.protected_value_algorithm, version.protected_value_crypto_version, version.confidence,
                               version.valid_from, version.valid_until, version.privacy_level,
                               COALESCE((SELECT transition.reason_code FROM memory_transition transition
                                         WHERE transition.memory_assertion_id = head.memory_assertion_id
                                           AND transition.learner_subject_id = subject.learner_subject_id
                                         ORDER BY transition.transitioned_at DESC LIMIT 1), '') AS latest_reason,
                               EXISTS (SELECT 1 FROM memory_source_link source
                                       WHERE source.memory_version_id = version.memory_version_id
                                         AND source.learner_subject_id = subject.learner_subject_id) AS has_source
                        FROM learner_subject subject
                        JOIN memory_head_projection head ON head.learner_subject_id = subject.learner_subject_id
                        JOIN memory_assertion assertion ON assertion.memory_assertion_id = head.memory_assertion_id
                            AND assertion.learner_subject_id = subject.learner_subject_id
                        JOIN memory_version version ON version.memory_version_id = """ + versionPredicate + """
                            AND version.memory_assertion_id = head.memory_assertion_id
                            AND version.learner_subject_id = subject.learner_subject_id
                        WHERE subject.subject_hash = :subjectHash
                          AND head.memory_assertion_id = :assertionId
                          AND (:activeOnly = FALSE OR (subject.status = 'ACTIVE'
                              AND head.status = 'ACTIVE'
                              AND NOT EXISTS (SELECT 1 FROM forget_tombstone tombstone
                                              WHERE tombstone.learner_subject_id = subject.learner_subject_id
                                                AND tombstone.memory_assertion_id = head.memory_assertion_id
                                                AND tombstone.scope = 'ASSERTION')))
                        """)
                .param("subjectHash", subjectHash)
                .param("assertionId", assertionId)
                .param("versionId", versionId)
                .param("activeOnly", activeOnly)
                .query(this::mapDetailRow)
                .optional();
    }

    private Subject lockSubject(String subjectHash) {
        return jdbcClient.sql("""
                        SELECT learner_subject_id, subject_hash
                        FROM learner_subject
                        WHERE subject_hash = :subjectHash AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("subjectHash", subjectHash)
                .query((resultSet, ignored) -> new Subject(
                        resultSet.getObject("learner_subject_id", UUID.class), resultSet.getString("subject_hash")))
                .optional()
                .orElseThrow(MemoryNotFoundException::new);
    }

    private ActivePolicy requireActivePolicyForCategory(UUID subjectId, String memoryType) {
        return jdbcClient.sql("""
                        SELECT revision, retention_days
                        FROM consent_policy_version
                        WHERE learner_subject_id = :subjectId
                          AND status = 'ACTIVE'
                          AND valid_from <= CURRENT_TIMESTAMP
                          AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                          AND allowed_categories @> jsonb_build_array(CAST(:memoryType AS text))
                        ORDER BY revision DESC, created_at DESC
                        LIMIT 1
                """)
                .param("subjectId", subjectId)
                .param("memoryType", memoryType)
                .query((resultSet, ignored) -> new ActivePolicy(
                        resultSet.getLong("revision"), resultSet.getObject("retention_days", Integer.class)))
                .optional()
                .orElseThrow(PolicyDisabledException::new);
    }

    private Head lockHead(UUID subjectId, UUID assertionId) {
        return jdbcClient.sql("""
                        SELECT head.memory_assertion_id, head.memory_version_id, head.status AS head_status,
                               assertion.memory_type, version.version_sequence, version.value_json::text AS value_json,
                               version.protected_value_ciphertext, version.protected_value_nonce,
                               version.protected_value_key_reference,
                               version.confidence, version.stability_score, version.privacy_level,
                               version.valid_from, version.valid_until
                        FROM memory_head_projection head
                        JOIN memory_assertion assertion ON assertion.memory_assertion_id = head.memory_assertion_id
                            AND assertion.learner_subject_id = head.learner_subject_id
                        JOIN memory_version version ON version.memory_version_id = head.memory_version_id
                            AND version.memory_assertion_id = head.memory_assertion_id
                            AND version.learner_subject_id = head.learner_subject_id
                        WHERE head.learner_subject_id = :subjectId
                          AND head.memory_assertion_id = :assertionId
                          AND head.status IN ('ACTIVE', 'STALE')
                          AND NOT EXISTS (SELECT 1 FROM forget_tombstone tombstone
                                          WHERE tombstone.learner_subject_id = head.learner_subject_id
                                            AND tombstone.memory_assertion_id = head.memory_assertion_id
                                            AND tombstone.scope = 'ASSERTION')
                        FOR UPDATE
                        """)
                .param("subjectId", subjectId)
                .param("assertionId", assertionId)
                .query((resultSet, ignored) -> new Head(
                        resultSet.getObject("memory_assertion_id", UUID.class),
                        resultSet.getObject("memory_version_id", UUID.class),
                        resultSet.getString("head_status"),
                        resultSet.getString("memory_type"),
                        resultSet.getLong("version_sequence"),
                        resultSet.getString("value_json"),
                        encryptedPayload(resultSet),
                        resultSet.getBigDecimal("confidence"),
                        resultSet.getBigDecimal("stability_score"),
                        resultSet.getString("privacy_level"),
                        resultSet.getTimestamp("valid_from").toInstant(),
                        resultSet.getTimestamp("valid_until") == null
                                ? null : resultSet.getTimestamp("valid_until").toInstant()))
                .optional()
                .orElseThrow(MemoryNotFoundException::new);
    }

    private static void requireActiveHead(Head head) {
        if (!"ACTIVE".equals(head.status())) {
            throw new MemoryNotFoundException();
        }
    }

    private static void requireStalePreference(Head head) {
        if (!"PREFERENCE".equals(head.memoryType())) {
            throw new MemoryNotFoundException();
        }
    }

    private static void requireExpectedVersion(Head head, String expectedVersion) {
        try {
            if (head.versionSequence() != Long.parseLong(expectedVersion)) {
                throw new StaleVersionException();
            }
        } catch (NumberFormatException exception) {
            throw new StaleVersionException();
        }
    }

    private UUID insertOpaqueCorrectionEvent(
            UUID subjectId,
            String subjectHash,
            String privacyClass,
            long consentRevision,
            String requestDigest,
            Instant at) {
        var eventId = UUID.randomUUID();
        var protectedSource = protectCorrectionSource(subjectHash, privacyClass, eventId, requestDigest);
        var protectedPayload = protectedSource.payload();
        jdbcClient.sql("""
                        INSERT INTO interaction_event (
                            event_id, schema_version, learner_subject_id, session_id, event_type, source_kind,
                            occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext,
                            payload_digest, trace_id, payload_key_reference, payload_nonce, payload_algorithm,
                            payload_crypto_version)
                        VALUES (:eventId, 'v1', :subjectId, 'management-correction', 'MEMORY_CORRECTION',
                                'EXPLICIT_DECLARATION', :occurredAt, :receivedAt, :privacyLevel, :consentRevision,
                                :payloadCiphertext, :payloadDigest, 'management', :payloadKeyReference,
                                :payloadNonce, :payloadAlgorithm, :payloadCryptoVersion)
                        """)
                .param("eventId", eventId)
                .param("subjectId", subjectId)
                .param("occurredAt", Timestamp.from(at))
                .param("receivedAt", Timestamp.from(at))
                .param("privacyLevel", privacyClass)
                .param("consentRevision", consentRevision)
                .param("payloadCiphertext", protectedPayload == null ? null : protectedPayload.serialize())
                .param("payloadDigest", requestDigest)
                .param("payloadKeyReference", protectedPayload == null ? null : protectedPayload.keyReference())
                .param("payloadNonce", protectedPayload == null ? null : protectedPayload.nonce())
                .param("payloadAlgorithm", protectedPayload == null ? null : EncryptedPayload.ALGORITHM)
                .param("payloadCryptoVersion", protectedPayload == null ? null : EncryptedPayload.CRYPTO_VERSION)
                .update();
        if (protectedSource.envelope() != null) {
            sourceEnvelopes.storeIfAbsent(subjectHash, eventId, "v1", protectedSource.envelope());
        }
        return eventId;
    }

    private ProtectedSource protectCorrectionSource(
            String subjectHash, String privacyClass, UUID eventId, String requestDigest) {
        if ("STANDARD".equals(privacyClass)) {
            return new ProtectedSource(null, null);
        }
        var binding = new PayloadProtector.SourceMaterialBinding(
                subjectHash, eventId, "v1", "interaction_event", KeyPurpose.INTERACTION_SOURCE);
        if (sourceEnvelopes == null) {
            return new ProtectedSource(EncryptedPayload.parse(payloadProtector.seal(
                    binding, requestDigest.getBytes(StandardCharsets.UTF_8))), null);
        }
        var sealed = payloadProtector.sealSource(binding, requestDigest.getBytes(StandardCharsets.UTF_8));
        return new ProtectedSource(EncryptedPayload.parse(sealed.framed()), sealed.envelope());
    }

    private void insertCorrectedVersion(
            UUID subjectId,
            String subjectHash,
            Head head,
            UUID correctedVersionId,
            String correctedJson,
            long consentRevision,
            Instant at) {
        var protectedValue = protectCorrectedValue(subjectHash, head.privacyClass(), correctedVersionId, correctedJson);
        jdbcClient.sql("""
                        INSERT INTO memory_version (
                            memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                            status, value_json, valid_from, valid_until, recorded_at, recorded_until,
                            confidence, stability_score, privacy_level, consent_revision, created_at,
                            protected_value_ciphertext, protected_value_nonce, protected_value_key_reference,
                            protected_value_algorithm, protected_value_crypto_version)
                        VALUES (:versionId, :assertionId, :subjectId, :sequence, 'ACTIVE',
                                CAST(:valueJson AS jsonb), :validFrom, :validUntil, :recordedAt, null,
                                :confidence, :stabilityScore, :privacyLevel, :consentRevision, :createdAt,
                                :protectedCiphertext, :protectedNonce, :protectedKeyReference,
                                :protectedAlgorithm, :protectedCryptoVersion)
                        """)
                .param("versionId", correctedVersionId)
                .param("assertionId", head.assertionId())
                .param("subjectId", subjectId)
                .param("sequence", Math.addExact(head.versionSequence(), 1))
                .param("valueJson", protectedValue == null ? correctedJson : null)
                .param("validFrom", Timestamp.from(head.validFrom()))
                .param("validUntil", head.validUntil() == null ? null : Timestamp.from(head.validUntil()))
                .param("recordedAt", Timestamp.from(at))
                .param("confidence", head.confidence())
                .param("stabilityScore", head.stabilityScore())
                .param("privacyLevel", head.privacyClass())
                .param("consentRevision", consentRevision)
                .param("createdAt", Timestamp.from(at))
                .param("protectedCiphertext", protectedValue == null ? null : protectedValue.ciphertextAndTag())
                .param("protectedNonce", protectedValue == null ? null : protectedValue.nonce())
                .param("protectedKeyReference", protectedValue == null ? null : protectedValue.keyReference())
                .param("protectedAlgorithm", protectedValue == null ? null : EncryptedPayload.ALGORITHM)
                .param("protectedCryptoVersion", protectedValue == null ? null : EncryptedPayload.CRYPTO_VERSION)
                .update();
    }

    private void insertReconfirmedVersion(
            UUID subjectId,
            String subjectHash,
            Head head,
            UUID renewedVersionId,
            ActivePolicy policy,
            Instant at) {
        var renewedValue = protectCorrectedValue(subjectHash, head.privacyClass(), renewedVersionId,
                reconsentValueJson(subjectHash, head));
        var validUntil = at.plus(retentionPolicy.effectiveFor(head.memoryType(), policy.retentionDays()));
        jdbcClient.sql("""
                        INSERT INTO memory_version (
                            memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                            status, value_json, valid_from, valid_until, recorded_at, recorded_until,
                            confidence, stability_score, privacy_level, consent_revision, created_at,
                            protected_value_ciphertext, protected_value_nonce, protected_value_key_reference,
                            protected_value_algorithm, protected_value_crypto_version)
                        VALUES (:versionId, :assertionId, :subjectId, :sequence, 'ACTIVE',
                                CAST(:valueJson AS jsonb), :validFrom, :validUntil, :recordedAt, null,
                                :confidence, :stabilityScore, :privacyLevel, :consentRevision, :createdAt,
                                :protectedCiphertext, :protectedNonce, :protectedKeyReference,
                                :protectedAlgorithm, :protectedCryptoVersion)
                        """)
                .param("versionId", renewedVersionId)
                .param("assertionId", head.assertionId())
                .param("subjectId", subjectId)
                .param("sequence", Math.addExact(head.versionSequence(), 1))
                .param("valueJson", renewedValue == null ? head.valueJson() : null)
                .param("validFrom", Timestamp.from(at))
                .param("validUntil", Timestamp.from(validUntil))
                .param("recordedAt", Timestamp.from(at))
                .param("confidence", head.confidence())
                .param("stabilityScore", head.stabilityScore())
                .param("privacyLevel", head.privacyClass())
                .param("consentRevision", policy.revision())
                .param("createdAt", Timestamp.from(at))
                .param("protectedCiphertext", renewedValue == null ? null : renewedValue.ciphertextAndTag())
                .param("protectedNonce", renewedValue == null ? null : renewedValue.nonce())
                .param("protectedKeyReference", renewedValue == null ? null : renewedValue.keyReference())
                .param("protectedAlgorithm", renewedValue == null ? null : EncryptedPayload.ALGORITHM)
                .param("protectedCryptoVersion", renewedValue == null ? null : EncryptedPayload.CRYPTO_VERSION)
                .update();
    }

    private String reconsentValueJson(String subjectHash, Head head) {
        if ("STANDARD".equals(head.privacyClass())) {
            return Objects.requireNonNull(head.valueJson(), "standard value json");
        }
        var protectedValue = Objects.requireNonNull(head.protectedValue(), "protected value");
        return new String(payloadProtector.decrypt(new PayloadProtector.PayloadBinding(
                subjectHash, head.versionId(), MemoryCandidate.VALUE_SCHEMA_VERSION, "memory_version",
                KeyPurpose.MEMORY_VERSION_VALUE), protectedValue), StandardCharsets.UTF_8);
    }

    private EncryptedPayload protectCorrectedValue(
            String subjectHash, String privacyClass, UUID versionId, String correctedJson) {
        if ("STANDARD".equals(privacyClass)) {
            return null;
        }
        return payloadProtector.encrypt(new PayloadProtector.PayloadBinding(
                subjectHash, versionId, MemoryCandidate.VALUE_SCHEMA_VERSION, "memory_version",
                KeyPurpose.MEMORY_VERSION_VALUE), correctedJson.getBytes(StandardCharsets.UTF_8));
    }

    private void insertCorrectionSourceLink(UUID subjectId, UUID versionId, UUID correctionEventId, Instant at) {
        jdbcClient.sql("""
                        INSERT INTO memory_source_link (
                            memory_source_link_id, memory_version_id, learner_subject_id,
                            source_event_id, source_schema_version, source_kind, created_at)
                        VALUES (:linkId, :versionId, :subjectId, :eventId, 'v1', 'INTERACTION_EVENT', :createdAt)
                        """)
                .param("linkId", UUID.randomUUID())
                .param("versionId", versionId)
                .param("subjectId", subjectId)
                .param("eventId", correctionEventId)
                .param("createdAt", Timestamp.from(at))
                .update();
    }

    private void insertTransition(
            UUID subjectId, UUID assertionId, UUID fromVersionId, UUID toVersionId, String transitionType, String reasonCode) {
        jdbcClient.sql("""
                        INSERT INTO memory_transition (
                            memory_transition_id, memory_assertion_id, learner_subject_id, from_memory_version_id,
                            to_memory_version_id, transition_type, reason_code, transitioned_at)
                        VALUES (:transitionId, :assertionId, :subjectId, :fromVersionId, :toVersionId,
                                :transitionType, :reasonCode, :transitionedAt)
                        """)
                .param("transitionId", UUID.randomUUID())
                .param("assertionId", assertionId)
                .param("subjectId", subjectId)
                .param("fromVersionId", fromVersionId)
                .param("toVersionId", toVersionId)
                .param("transitionType", transitionType)
                .param("reasonCode", reasonCode)
                .param("transitionedAt", Timestamp.from(Instant.now()))
                .update();
    }

    private long incrementEpoch(UUID subjectId) {
        jdbcClient.sql("""
                        INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at)
                        VALUES (:subjectId, 0, 0, CURRENT_TIMESTAMP)
                        ON CONFLICT (learner_subject_id) DO NOTHING
                        """)
                .param("subjectId", subjectId)
                .update();
        return jdbcClient.sql("""
                        UPDATE learner_epoch
                        SET memory_epoch = memory_epoch + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE learner_subject_id = :subjectId
                        RETURNING memory_epoch
                        """)
                .param("subjectId", subjectId)
                .query(Long.class)
                .single();
    }

    private void updateHead(UUID assertionId, UUID subjectId, UUID versionId, String status, long memoryEpoch) {
        jdbcClient.sql("""
                        UPDATE memory_head_projection
                        SET memory_version_id = :versionId, status = :status, memory_epoch = :memoryEpoch,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE memory_assertion_id = :assertionId AND learner_subject_id = :subjectId
                        """)
                .param("versionId", versionId)
                .param("status", status)
                .param("memoryEpoch", memoryEpoch)
                .param("assertionId", assertionId)
                .param("subjectId", subjectId)
                .update();
    }

    private void enqueuePurge(UUID subjectId, UUID assertionId, long memoryEpoch) {
        var payload = "{\"learner_subject_id\":\"" + subjectId + "\",\"memory_assertion_id\":\""
                + assertionId + "\",\"memory_epoch\":" + memoryEpoch + '}';
        jdbcClient.sql("""
                        INSERT INTO transactional_outbox (outbox_id, aggregate_id, event_type, payload, created_at)
                        VALUES (:outboxId, :aggregateId, :eventType, CAST(:payload AS jsonb), CURRENT_TIMESTAMP)
                        """)
                .param("outboxId", UUID.randomUUID())
                .param("aggregateId", assertionId)
                .param("eventType", PROJECTION_PURGE)
                .param("payload", payload)
                .update();
    }

    private Optional<Receipt> findReceipt(UUID subjectId, String keyDigest) {
        return jdbcClient.sql("""
                        SELECT memory_assertion_id, action_type, request_digest, result_memory_version_id,
                               result_head_status
                        FROM memory_management_action_receipt
                        WHERE learner_subject_id = :subjectId AND idempotency_key_digest = :keyDigest
                        """)
                .param("subjectId", subjectId)
                .param("keyDigest", keyDigest)
                .query((resultSet, ignored) -> new Receipt(
                        resultSet.getObject("memory_assertion_id", UUID.class),
                        resultSet.getString("action_type"),
                        resultSet.getString("request_digest"),
                        resultSet.getObject("result_memory_version_id", UUID.class),
                        resultSet.getString("result_head_status")))
                .optional();
    }

    private void insertReceipt(
            UUID subjectId,
            UUID assertionId,
            String action,
            String keyDigest,
            String requestDigest,
            UUID resultVersionId,
            String resultStatus) {
        jdbcClient.sql("""
                        INSERT INTO memory_management_action_receipt (
                            memory_management_action_receipt_id, learner_subject_id, memory_assertion_id,
                            action_type, idempotency_key_digest, request_digest, result_memory_version_id,
                            result_head_status, created_at)
                        VALUES (:receiptId, :subjectId, :assertionId, :action, :keyDigest, :requestDigest,
                                :resultVersionId, :resultStatus, CURRENT_TIMESTAMP)
                        """)
                .param("receiptId", UUID.randomUUID())
                .param("subjectId", subjectId)
                .param("assertionId", assertionId)
                .param("action", action)
                .param("keyDigest", keyDigest)
                .param("requestDigest", requestDigest)
                .param("resultVersionId", resultVersionId)
                .param("resultStatus", resultStatus)
                .update();
    }

    private ListRow mapListRow(ResultSet resultSet, int ignored) throws SQLException {
        var summary = new MemorySummary(
                resultSet.getObject("memory_assertion_id", UUID.class),
                Long.toString(resultSet.getLong("version_sequence")),
                resultSet.getString("memory_type"),
                resultSet.getString("status"),
                confirmationStatus(resultSet.getString("latest_reason")),
                minimizeListDisplayValue(resultSet),
                confidenceBand(resultSet.getBigDecimal("confidence")),
                resultSet.getTimestamp("valid_from").toInstant(),
                timestamp(resultSet, "valid_until"));
        return new ListRow(summary, resultSet.getTimestamp("updated_at").toInstant());
    }

    private MemoryDetail mapDetailRow(ResultSet resultSet, int ignored) throws SQLException {
        var hasSource = resultSet.getBoolean("has_source");
        return new MemoryDetail(
                resultSet.getObject("memory_assertion_id", UUID.class),
                Long.toString(resultSet.getLong("version_sequence")),
                resultSet.getString("memory_type"),
                resultSet.getString("status"),
                confirmationStatus(resultSet.getString("latest_reason")),
                parseValue(readValueJson(resultSet)),
                confidenceBand(resultSet.getBigDecimal("confidence")),
                resultSet.getTimestamp("valid_from").toInstant(),
                timestamp(resultSet, "valid_until"),
                "OPTIONAL",
                resultSet.getString("privacy_level"),
                List.of(hasSource ? "linked-source" : "recorded-memory"),
                minimizeReason(resultSet.getString("latest_reason")),
                List.of());
    }

    private Map<String, Object> parseValue(String valueJson) {
        try {
            return objectMapper.readValue(valueJson, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("stored memory value is invalid", exception);
        }
    }

    private String serializeValue(Map<String, Object> value) {
        try {
            return objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("correctedValue", exception);
        }
    }

    private static String confidenceBand(java.math.BigDecimal confidence) {
        if (confidence.compareTo(new java.math.BigDecimal("0.80")) >= 0) {
            return "HIGH";
        }
        if (confidence.compareTo(new java.math.BigDecimal("0.50")) >= 0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String confirmationStatus(String reason) {
        return "USER_CONFIRMED".equals(reason) ? "CONFIRMED" : "UNCONFIRMED";
    }

    private static String confirmationStatusForAction(String action) {
        return "CONFIRM".equals(action) ? "CONFIRMED" : "UNCONFIRMED";
    }

    private static String reasonForAction(String action) {
        return switch (action) {
            case "CONFIRM" -> "USER_CONFIRMED";
            case "CORRECT" -> "USER_CORRECTED";
            case "DISABLE" -> "USER_DISABLED";
            default -> throw new IllegalStateException("invalid receipt action");
        };
    }

    private Map<String, Object> minimizeListDisplayValue(ResultSet resultSet) throws SQLException {
        var privacyClass = resultSet.getString("privacy_level");
        rejectProtectedPlaintext(privacyClass, resultSet.getString("value_json"));
        return "HIGH".equals(privacyClass) ? Map.of() : parseValue(readValueJson(resultSet));
    }

    private String readValueJson(ResultSet resultSet) throws SQLException {
        var stored = resultSet.getString("value_json");
        var privacyClass = resultSet.getString("privacy_level");
        rejectProtectedPlaintext(privacyClass, stored);
        if (stored != null) {
            return stored;
        }
        if (!"SENSITIVE".equals(privacyClass) && !"HIGH".equals(privacyClass)) {
            throw new IllegalStateException("stored memory value is missing");
        }
        var payload = new EncryptedPayload(
                resultSet.getString("protected_value_key_reference"),
                resultSet.getBytes("protected_value_nonce"),
                resultSet.getBytes("protected_value_ciphertext"));
        if (!EncryptedPayload.ALGORITHM.equals(resultSet.getString("protected_value_algorithm"))
                || resultSet.getInt("protected_value_crypto_version") != EncryptedPayload.CRYPTO_VERSION) {
            throw new SecurityException("encrypted payload rejected");
        }
        var plaintext = payloadProtector.decrypt(new PayloadProtector.PayloadBinding(
                resultSet.getString("subject_hash"), resultSet.getObject("memory_version_id", UUID.class),
                MemoryCandidate.VALUE_SCHEMA_VERSION, "memory_version", KeyPurpose.MEMORY_VERSION_VALUE), payload);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static void rejectProtectedPlaintext(String privacyClass, String stored) {
        if (("SENSITIVE".equals(privacyClass) || "HIGH".equals(privacyClass)) && stored != null) {
            throw new SecurityException("protected memory value contains plaintext");
        }
    }

    private static String minimizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason;
    }

    private static Instant timestamp(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String digest(String input) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            var encoded = new StringBuilder(64);
            for (var value : bytes) {
                encoded.append(String.format("%02x", value));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Cursor parseCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            var delimiter = decoded.lastIndexOf('|');
            if (delimiter < 1 || delimiter == decoded.length() - 1) {
                throw new IllegalArgumentException("cursor");
            }
            return new Cursor(Instant.parse(decoded.substring(0, delimiter)), UUID.fromString(decoded.substring(delimiter + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor", exception);
        }
    }

    private static String encodeCursor(Instant updatedAt, UUID assertionId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (updatedAt + "|" + assertionId).getBytes(StandardCharsets.UTF_8));
    }

    private static EncryptedPayload encryptedPayload(ResultSet resultSet) throws SQLException {
        var ciphertext = resultSet.getBytes("protected_value_ciphertext");
        if (ciphertext == null) {
            return null;
        }
        return new EncryptedPayload(
                resultSet.getString("protected_value_key_reference"),
                resultSet.getBytes("protected_value_nonce"), ciphertext);
    }

    @FunctionalInterface
    private interface VersionAction {
        ActionOutcome run();
    }

    private record Subject(UUID id, String subjectHash) {
    }

    private record Head(
            UUID assertionId,
            UUID versionId,
            String status,
            String memoryType,
            long versionSequence,
            String valueJson,
            EncryptedPayload protectedValue,
            java.math.BigDecimal confidence,
            java.math.BigDecimal stabilityScore,
            String privacyClass,
            Instant validFrom,
            Instant validUntil) {
    }

    private record ActivePolicy(long revision, Integer retentionDays) {
    }

    private record ProtectedSource(
            EncryptedPayload payload, InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
    }

    private record Receipt(
            UUID assertionId, String action, String requestDigest, UUID resultVersionId, String resultStatus) {
    }

    private record ActionOutcome(UUID versionId, String status) {
    }

    private record Cursor(Instant updatedAt, UUID memoryAssertionId) {
    }

    private record ListRow(MemorySummary summary, Instant updatedAt) {
    }
}
