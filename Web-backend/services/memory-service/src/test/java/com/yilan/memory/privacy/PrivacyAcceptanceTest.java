package com.yilan.memory.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yilan.memory.adapter.postgres.JdbcCandidateRepository;
import com.yilan.memory.application.governance.GovernCandidateUseCase.CandidateAppendResult;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.GovernanceDecision;
import com.yilan.memory.domain.governance.GovernanceDecision.Decision;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PrivacyAcceptanceTest extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final String SUBJECT = "m4-private-subject-sentinel";
    private static final String SOURCE_PAYLOAD = "M4_PRIVATE_SOURCE_PAYLOAD";
    private static final String CANDIDATE_CIPHERTEXT = "M4_PRIVATE_CANDIDATE_CIPHERTEXT";

    @Autowired
    private JdbcClient jdbcClient;

    private UUID eventId;

    @BeforeEach
    void setUp() {
        clearAuthorityRows();
        insertLearner();
        eventId = insertEvent();
    }

    @AfterEach
    void cleanUp() {
        clearAuthorityRows();
    }

    @Test
    void candidateAuditWriterPersistsFiniteGovernanceEvidenceWithoutPrivateSentinels() throws Exception {
        var candidate = new MemoryCandidate(
                UUID.randomUUID(), SUBJECT, 3, MemoryType.PREFERENCE, "m4-private-key-sentinel",
                "{\"answer_style\":\"concise\"}", new BigDecimal("0.90"), new BigDecimal("0.80"),
                PrivacyLevel.STANDARD, NOW, List.of(new MemoryCandidate.SourceReference(eventId, "v1")),
                CANDIDATE_CIPHERTEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var decision = new GovernanceDecision(
                Decision.ACCEPTED, "rules-m4-privacy-v1", List.of("RULES_SATISFIED"), NOW);

        CandidateAppendResult appended = new JdbcCandidateRepository(jdbcClient)
                .appendIfAbsent(candidate, decision, NOW);
        var metadata = jdbcClient.sql("SELECT redacted_metadata::text FROM memory_audit_event")
                .query(String.class)
                .single();
        var metadataJson = JSON.readTree(metadata);
        var metadataKeys = new HashSet<String>();
        metadataJson.fieldNames().forEachRemaining(metadataKeys::add);
        var reasonCodes = new ArrayList<String>();
        metadataJson.path("reason_codes").forEach(node -> reasonCodes.add(node.asText()));

        assertAll(
                () -> assertThat(appended.inserted()).isTrue(),
                () -> assertThat(metadataJson.isObject()).isTrue(),
                () -> assertThat(metadataKeys).isEqualTo(Set.of(
                        "candidate_id", "memory_type", "rule_set_version", "outcome", "reason_codes")),
                () -> assertThat(metadataJson.path("candidate_id").asText())
                        .isEqualTo(candidate.candidateId().toString()),
                () -> assertThat(metadataJson.path("memory_type").asText()).isEqualTo("PREFERENCE"),
                () -> assertThat(metadataJson.path("rule_set_version").asText()).isEqualTo("rules-m4-privacy-v1"),
                () -> assertThat(metadataJson.path("outcome").asText()).isEqualTo("ACCEPTED"),
                () -> assertThat(reasonCodes).containsExactly("RULES_SATISFIED"),
                () -> assertThat(metadata).doesNotContain(
                        SUBJECT,
                        SOURCE_PAYLOAD,
                        CANDIDATE_CIPHERTEXT,
                        "m4-private-key-sentinel"));
    }

    @Test
    void auditAuthoritySchemaRetainsRequiredColumnsAndRejectsMutation() {
        var columns = jdbcClient.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'memory_audit_event'
                        """)
                .query(String.class)
                .list();
        var auditId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO memory_audit_event (
                            memory_audit_event_id, learner_subject_id, audit_type, actor_type,
                            correlation_id, redacted_metadata, created_at)
                        SELECT :auditId, learner_subject_id, 'M4_PRIVACY_ACCEPTANCE', 'SYSTEM',
                               'm4-audit-correlation', CAST('{\"outcome\":\"ACCEPTED\"}' AS jsonb), :createdAt
                        FROM learner_subject
                        WHERE subject_hash = :subjectHash
                        """)
                .param("auditId", auditId)
                .param("createdAt", Timestamp.from(NOW))
                .param("subjectHash", SUBJECT)
                .update();

        assertAll(
                () -> assertThat(columns).contains(
                        "memory_audit_event_id",
                        "learner_subject_id",
                        "audit_type",
                        "actor_type",
                        "correlation_id",
                        "redacted_metadata",
                        "created_at"),
                () -> assertThatThrownBy(() -> jdbcClient.sql("""
                                UPDATE memory_audit_event
                                SET audit_type = 'MUTATED'
                                WHERE memory_audit_event_id = :auditId
                                """)
                                .param("auditId", auditId)
                                .update())
                        .isInstanceOf(RuntimeException.class));
    }

    private void insertLearner() {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:id, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("subjectHash", SUBJECT)
                .param("createdAt", Timestamp.from(NOW))
                .update();
    }

    private UUID insertEvent() {
        var id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO interaction_event (
                            event_id, schema_version, learner_subject_id, session_id, event_type, source_kind,
                            occurred_at, received_at, privacy_level, consent_revision,
                            payload_ciphertext, payload_digest, trace_id, payload_key_reference, payload_nonce,
                            payload_algorithm, payload_crypto_version)
                        SELECT :eventId, 'v1', learner_subject_id, 'm4-private-session', 'PREFERENCE',
                               'EXPLICIT_DECLARATION', :occurredAt, :receivedAt, 'STANDARD', 3,
                               :payloadCiphertext, :payloadDigest, 'm4-private-trace', NULL, NULL, NULL, NULL
                        FROM learner_subject
                        WHERE subject_hash = :subjectHash
                        """)
                .param("eventId", id)
                .param("occurredAt", Timestamp.from(NOW))
                .param("receivedAt", Timestamp.from(NOW))
                .param("payloadCiphertext", SOURCE_PAYLOAD.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .param("payloadDigest", "0".repeat(64))
                .param("subjectHash", SUBJECT)
                .update();
        return id;
    }

    private void clearAuthorityRows() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }
}
