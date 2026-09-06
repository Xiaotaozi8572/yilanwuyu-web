package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.application.consent.ConsentService;
import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.consent.ConsentPolicy.PolicyStatus;
import com.yilan.memory.domain.consent.ConsentPolicy.SubjectStatus;
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
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL adapter for the newest policy and append-only self-service
 * changes. The write entry point owns the authority transaction; application
 * code never issues JDBC directly.
 */
@Repository
public class JdbcConsentRepository implements ConsentQuery.PolicyReader, ConsentService.ConsentStore {

    private final JdbcClient jdbcClient;

    public JdbcConsentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ConsentPolicy> findForSubject(String subjectHash) {
        return jdbcClient.sql("""
                        SELECT subject.status AS subject_status,
                               policy.revision AS policy_revision,
                               policy.status AS policy_status,
                               policy.allowed_categories::text AS allowed_categories,
                               policy.valid_from AS valid_from,
                               policy.valid_until AS valid_until
                        FROM learner_subject subject
                        LEFT JOIN LATERAL (
                            SELECT revision, status, allowed_categories, valid_from, valid_until
                            FROM consent_policy_version
                            WHERE learner_subject_id = subject.learner_subject_id
                            ORDER BY revision DESC, created_at DESC
                            LIMIT 1
                        ) policy ON TRUE
                        WHERE subject.subject_hash = :subjectHash
                        """)
                .param("subjectHash", subjectHash)
                .query(this::mapPolicy)
                .optional();
    }

    @Override
    public Optional<ConsentService.StoredConsent> find(String subjectHash) {
        return jdbcClient.sql("""
                        SELECT policy.revision,
                               policy.status,
                               policy.allowed_categories::text AS allowed_categories,
                               policy.valid_from
                        FROM learner_subject subject
                        JOIN LATERAL (
                            SELECT revision, status, allowed_categories, valid_from
                            FROM consent_policy_version
                            WHERE learner_subject_id = subject.learner_subject_id
                            ORDER BY revision DESC, created_at DESC
                            LIMIT 1
                        ) policy ON TRUE
                        WHERE subject.subject_hash = :subjectHash
                          AND subject.status = 'ACTIVE'
                        """)
                .param("subjectHash", subjectHash)
                .query((resultSet, rowNumber) -> new ConsentService.StoredConsent(
                        resultSet.getLong("revision"),
                        PolicyStatus.ACTIVE.name().equals(resultSet.getString("status")),
                        parseCategories(resultSet.getString("allowed_categories")),
                        resultSet.getTimestamp("valid_from").toInstant()))
                .optional();
    }

    /**
     * Serializes concurrent replacement for a subject with a row lock. All
     * four authority actions occur in this one transaction: append policy,
     * advance consent epoch, set its immediate gate through policy state, and
     * enqueue an identifier-only projection purge.
     */
    @Override
    @Transactional
    public ConsentService.Commit append(
            String subjectHash,
            long expectedRevision,
            ConsentService.Policy policy) {
        return appendPolicy(lockActiveSubject(subjectHash, policy.effectiveAt()), expectedRevision, policy);
    }

    @Override
    @Transactional
    public ConsentService.AppendResult append(
            String subjectHash,
            long expectedRevision,
            ConsentService.Policy policy,
            String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        var learnerSubjectId = lockActiveSubject(subjectHash, policy.effectiveAt());
        var keyDigest = digest(idempotencyKey);
        var requestDigest = canonicalRequestDigest(expectedRevision, policy);
        var existing = findReceipt(learnerSubjectId, keyDigest);
        if (existing.isPresent()) {
            var receipt = existing.orElseThrow();
            if (!receipt.requestDigest().equals(requestDigest)) {
                throw new ConsentService.IdempotencyConflictException();
            }
            return new ConsentService.AppendResult(resultPolicy(learnerSubjectId, receipt.resultPolicyRevision()), 0L, true);
        }

        var committed = appendPolicy(learnerSubjectId, expectedRevision, policy);
        insertReceipt(learnerSubjectId, keyDigest, requestDigest, committed.revision(), policy.effectiveAt());
        return new ConsentService.AppendResult(new ConsentService.StoredConsent(
                committed.revision(), policy.longTermEnabled(), policy.allowedCategories(), policy.effectiveAt()),
                committed.consentEpoch(), false);
    }

    /** Locks an ACTIVE authority subject before every append or receipt lookup. */
    private UUID lockActiveSubject(String subjectHash, Instant createdAt) {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:learnerSubjectId, :subjectHash, 'ACTIVE', :createdAt)
                        ON CONFLICT (subject_hash) DO NOTHING
                """)
                .param("learnerSubjectId", UUID.randomUUID())
                .param("subjectHash", subjectHash)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        return jdbcClient.sql("""
                        SELECT learner_subject_id
                        FROM learner_subject
                        WHERE subject_hash = :subjectHash AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("subjectHash", subjectHash)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new UnknownSubjectException(subjectHash));
    }

    private ConsentService.Commit appendPolicy(
            UUID learnerSubjectId,
            long expectedRevision,
            ConsentService.Policy policy) {
        var actualRevision = jdbcClient.sql("""
                        SELECT revision
                        FROM consent_policy_version
                        WHERE learner_subject_id = :learnerSubjectId
                        ORDER BY revision DESC, created_at DESC
                        LIMIT 1
                        """)
                .param("learnerSubjectId", learnerSubjectId)
                .query(Long.class)
                .optional()
                .orElse(0L);
        if (actualRevision != expectedRevision) {
            throw new ConsentService.StaleConsentVersionException();
        }

        var revision = Math.addExact(actualRevision, 1L);
        var validUntil = policy.retentionDays() == null
                ? null
                : policy.effectiveAt().plus(java.time.Duration.ofDays(policy.retentionDays()));
        jdbcClient.sql("""
                        INSERT INTO consent_policy_version (
                            consent_policy_version_id, learner_subject_id, revision, status, allowed_categories,
                            valid_from, valid_until, created_at, purpose_text_version, source_capture_enabled,
                            retention_days, actor_role)
                        VALUES (
                            :policyId, :learnerSubjectId, :revision, :status, CAST(:categories AS jsonb),
                            :validFrom, :validUntil, :createdAt, :purposeTextVersion, :sourceCaptureEnabled,
                            :retentionDays, :actorRole)
                        """)
                .param("policyId", UUID.randomUUID())
                .param("learnerSubjectId", learnerSubjectId)
                .param("revision", revision)
                .param("status", policy.longTermEnabled() ? PolicyStatus.ACTIVE.name() : PolicyStatus.REVOKED.name())
                .param("categories", categoriesJson(policy.allowedCategories()))
                .param("validFrom", Timestamp.from(policy.effectiveAt()))
                .param("validUntil", validUntil == null ? null : Timestamp.from(validUntil))
                .param("createdAt", Timestamp.from(policy.effectiveAt()))
                .param("purposeTextVersion", policy.purposeTextVersion())
                .param("sourceCaptureEnabled", policy.sourceCaptureEnabled())
                .param("retentionDays", policy.retentionDays())
                .param("actorRole", policy.actorRole().name())
                .update();
        var consentEpoch = jdbcClient.sql("""
                        INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at)
                        VALUES (:learnerSubjectId, 0, 1, :updatedAt)
                        ON CONFLICT (learner_subject_id) DO UPDATE
                        SET consent_epoch = learner_epoch.consent_epoch + 1,
                            updated_at = EXCLUDED.updated_at
                        RETURNING consent_epoch
                        """)
                .param("learnerSubjectId", learnerSubjectId)
                .param("updatedAt", Timestamp.from(policy.effectiveAt()))
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO transactional_outbox (outbox_id, aggregate_id, event_type, payload, created_at)
                        VALUES (:outboxId, :aggregateId, 'CONSENT_PROJECTION_PURGE', CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("outboxId", UUID.randomUUID())
                .param("aggregateId", learnerSubjectId)
                .param("payload", "{\"learner_subject_id\":\"" + learnerSubjectId
                        + "\",\"consent_epoch\":" + consentEpoch + "}")
                .param("createdAt", Timestamp.from(policy.effectiveAt()))
                .update();
        return new ConsentService.Commit(revision, consentEpoch);
    }

    private Optional<Receipt> findReceipt(UUID learnerSubjectId, String keyDigest) {
        return jdbcClient.sql("""
                        SELECT request_digest, result_policy_revision
                        FROM consent_action_receipt
                        WHERE learner_subject_id = :learnerSubjectId
                          AND idempotency_key_digest = :keyDigest
                        """)
                .param("learnerSubjectId", learnerSubjectId)
                .param("keyDigest", keyDigest)
                .query((resultSet, rowNumber) -> new Receipt(
                        resultSet.getString("request_digest"), resultSet.getLong("result_policy_revision")))
                .optional();
    }

    private ConsentService.StoredConsent resultPolicy(UUID learnerSubjectId, long revision) {
        return jdbcClient.sql("""
                        SELECT revision, status, allowed_categories::text AS allowed_categories, valid_from
                        FROM consent_policy_version
                        WHERE learner_subject_id = :learnerSubjectId AND revision = :revision
                        """)
                .param("learnerSubjectId", learnerSubjectId)
                .param("revision", revision)
                .query((resultSet, rowNumber) -> new ConsentService.StoredConsent(
                        resultSet.getLong("revision"),
                        PolicyStatus.ACTIVE.name().equals(resultSet.getString("status")),
                        parseCategories(resultSet.getString("allowed_categories")),
                        resultSet.getTimestamp("valid_from").toInstant()))
                .optional()
                .orElseThrow(() -> new IllegalStateException("stored consent receipt result is unavailable"));
    }

    private void insertReceipt(
            UUID learnerSubjectId,
            String keyDigest,
            String requestDigest,
            long resultPolicyRevision,
            Instant createdAt) {
        jdbcClient.sql("""
                        INSERT INTO consent_action_receipt (
                            consent_action_receipt_id, learner_subject_id, idempotency_key_digest,
                            request_digest, result_policy_revision, created_at)
                        VALUES (:receiptId, :learnerSubjectId, :keyDigest, :requestDigest,
                                :resultPolicyRevision, :createdAt)
                        """)
                .param("receiptId", UUID.randomUUID())
                .param("learnerSubjectId", learnerSubjectId)
                .param("keyDigest", keyDigest)
                .param("requestDigest", requestDigest)
                .param("resultPolicyRevision", resultPolicyRevision)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
    }

    private static String canonicalRequestDigest(long expectedRevision, ConsentService.Policy policy) {
        var categories = policy.allowedCategories().stream().map(Enum::name).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return digest("expected_revision=" + expectedRevision
                + "\nlong_term_enabled=" + policy.longTermEnabled()
                + "\nallowed_categories=" + categories
                + "\nretention_days=" + (policy.retentionDays() == null ? "null" : policy.retentionDays()));
    }

    private static String digest(String input) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private ConsentPolicy mapPolicy(ResultSet resultSet, int rowNumber) throws SQLException {
        var subjectStatus = SubjectStatus.valueOf(resultSet.getString("subject_status"));
        var revision = resultSet.getObject("policy_revision", Long.class);
        if (revision == null) {
            return ConsentPolicy.missing(subjectStatus);
        }
        return new ConsentPolicy(
                subjectStatus,
                PolicyStatus.valueOf(resultSet.getString("policy_status")),
                revision,
                parseCategories(resultSet.getString("allowed_categories")),
                resultSet.getTimestamp("valid_from").toInstant(),
                resultSet.getTimestamp("valid_until") == null
                        ? null
                        : resultSet.getTimestamp("valid_until").toInstant());
    }

    private static EnumSet<MemoryCategory> parseCategories(String json) {
        var categories = EnumSet.noneOf(MemoryCategory.class);
        if (json == null) {
            return categories;
        }

        var index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index++) != '[') {
            return EnumSet.noneOf(MemoryCategory.class);
        }
        index = skipWhitespace(json, index);
        if (index < json.length() && json.charAt(index) == ']') {
            return skipWhitespace(json, index + 1) == json.length()
                    ? categories
                    : EnumSet.noneOf(MemoryCategory.class);
        }

        while (index < json.length()) {
            if (json.charAt(index) != '"') {
                return EnumSet.noneOf(MemoryCategory.class);
            }
            var nameStart = ++index;
            while (index < json.length() && json.charAt(index) != '"') {
                var character = json.charAt(index);
                if (character == '\\' || character < 0x20) {
                    return EnumSet.noneOf(MemoryCategory.class);
                }
                index++;
            }
            if (index >= json.length()) {
                return EnumSet.noneOf(MemoryCategory.class);
            }
            try {
                categories.add(MemoryCategory.valueOf(json.substring(nameStart, index)));
            } catch (IllegalArgumentException ignored) {
                return EnumSet.noneOf(MemoryCategory.class);
            }

            index = skipWhitespace(json, index + 1);
            if (index >= json.length()) {
                return EnumSet.noneOf(MemoryCategory.class);
            }
            if (json.charAt(index) == ']') {
                return skipWhitespace(json, index + 1) == json.length()
                        ? categories
                        : EnumSet.noneOf(MemoryCategory.class);
            }
            if (json.charAt(index++) != ',') {
                return EnumSet.noneOf(MemoryCategory.class);
            }
            index = skipWhitespace(json, index);
        }
        return EnumSet.noneOf(MemoryCategory.class);
    }

    private static String categoriesJson(java.util.Set<MemoryCategory> categories) {
        return categories.stream()
                .map(Enum::name)
                .sorted()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && isJsonWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean isJsonWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r';
    }

    public static final class UnknownSubjectException extends RuntimeException {
        public UnknownSubjectException(String subjectHash) {
            super("unknown consent subject");
        }
    }

    private record Receipt(String requestDigest, long resultPolicyRevision) {
    }
}
