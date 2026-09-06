package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.management.MemoryManagementRepository;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.application.privacy.RetentionPolicy;
import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JdbcMemoryManagementRepositoryTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Autowired
    private JdbcMemoryManagementRepository repository;

    @Autowired
    private JdbcForgetRepository forgetRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }

    @Test
    void correctionAppendsVersionOpaqueSourceLinkEpochPurgeAndDigestOnlyReceiptIdempotently() {
        var fixture = insertMemory("a".repeat(43), "STANDARD");
        var mutation = new MemoryManagementRepository.Correction(
                fixture.subjectHash(), fixture.assertionId(), "1", "k".repeat(16),
                Map.of("preference", "stepwise"), "user-correction");

        var corrected = repository.correct(mutation);
        var replayed = repository.correct(mutation);

        assertThat(corrected.detail().version()).isEqualTo("2");
        assertThat(replayed.detail().version()).isEqualTo("2");
        assertThat(count("memory_version")).isEqualTo(2);
        assertThat(count("interaction_event")).isEqualTo(1);
        assertThat(count("memory_source_link")).isEqualTo(1);
        assertThat(transitions()).containsExactly("SUPERSEDE:USER_CORRECTED");
        assertThat(memoryEpoch()).isEqualTo(1);
        assertThat(outboxPayload()).contains("memory_assertion_id", "memory_epoch")
                .doesNotContain(fixture.subjectHash(), "stepwise", "user-correction");
        assertThat(receipt()).contains("CORRECT")
                .doesNotContain("k".repeat(16), "stepwise", "user-correction");

        assertThatThrownBy(() -> repository.correct(new MemoryManagementRepository.Correction(
                fixture.subjectHash(), fixture.assertionId(), "2", "k".repeat(16), Map.of("preference", "other"), null)))
                .isInstanceOf(MemoryManagementRepository.IdempotencyConflictException.class);
    }

    @Test
    void confirmationAndDisableAppendTransitionsAdvanceEpochAndUseSubjectBoundNotFound() {
        var fixture = insertMemory("b".repeat(43), "STANDARD");

        repository.confirm(new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "c".repeat(16)));
        repository.disable(new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "d".repeat(16)));

        assertThat(transitions()).containsExactly("ACTIVATE:USER_CONFIRMED", "ARCHIVE:USER_DISABLED");
        assertThat(memoryEpoch()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT status FROM memory_head_projection")
                .query(String.class).single()).isEqualTo("DISABLED");
        assertThat(count("memory_management_action_receipt")).isEqualTo(2);
        assertThatThrownBy(() -> repository.disable(new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), UUID.randomUUID(), "1", "z".repeat(16))))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);
    }

    @Test
    void stalePreferenceConfirmationAppendsARenewedVersionWithBoundedWindow() {
        var fixture = insertMemory("c".repeat(43), "STANDARD");
        jdbcClient.sql("""
                        UPDATE memory_head_projection
                        SET status = 'STALE'
                        WHERE memory_assertion_id = :assertionId
                        """)
                .param("assertionId", fixture.assertionId()).update();

        var result = repository.confirm(new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "r".repeat(16)));

        assertThat(result.detail().version()).isEqualTo("2");
        assertThat(result.detail().status()).isEqualTo("ACTIVE");
        assertThat(transitions()).containsExactly("ACTIVATE:USER_RECONFIRMED");
        assertThat(jdbcClient.sql("SELECT count(*) FROM memory_version")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        SELECT valid_until IS NOT NULL
                           AND valid_until > valid_from
                           AND valid_until <= valid_from + INTERVAL '180 days'
                        FROM memory_version
                        WHERE version_sequence = 2
                        """).query(Boolean.class).single()).isTrue();
    }

    @Test
    void disabledMemoryCannotBeReactivatedButTheOriginalDisableStillReplays() {
        var fixture = insertMemory("d".repeat(43), "STANDARD");
        var originalDisable = new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "x".repeat(16));

        repository.disable(originalDisable);

        assertThatThrownBy(() -> repository.confirm(new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "y".repeat(16))))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);
        assertThatThrownBy(() -> repository.correct(new MemoryManagementRepository.Correction(
                fixture.subjectHash(), fixture.assertionId(), "1", "z".repeat(16),
                Map.of("preference", "reactivate"), null)))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);

        assertThat(repository.disable(originalDisable).detail().status()).isEqualTo("DISABLED");
        assertThat(jdbcClient.sql("SELECT status FROM memory_head_projection")
                .query(String.class).single()).isEqualTo("DISABLED");
        assertThat(transitions()).containsExactly("ARCHIVE:USER_DISABLED");
    }

    @Test
    void confirmationAndCorrectionRequireTheCurrentCategoryButDisableRemainsAvailable() {
        var fixture = insertMemory("e".repeat(43), "STANDARD");
        jdbcClient.sql("UPDATE consent_policy_version SET allowed_categories = CAST('[\"MASTERY\"]' AS jsonb)").update();

        assertThatThrownBy(() -> repository.confirm(new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "f".repeat(16))))
                .isInstanceOf(MemoryManagementRepository.PolicyDisabledException.class);
        assertThatThrownBy(() -> repository.correct(new MemoryManagementRepository.Correction(
                fixture.subjectHash(), fixture.assertionId(), "1", "g".repeat(16),
                Map.of("preference", "blocked"), null)))
                .isInstanceOf(MemoryManagementRepository.PolicyDisabledException.class);

        assertThat(repository.disable(new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "h".repeat(16))).detail().status())
                .isEqualTo("DISABLED");
    }

    @Test
    void replayReturnsTheOriginalActionResultRatherThanALaterHeadVersion() {
        var fixture = insertMemory("i".repeat(43), "STANDARD");
        var confirmation = new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "j".repeat(16));
        repository.confirm(confirmation);
        repository.correct(new MemoryManagementRepository.Correction(
                fixture.subjectHash(), fixture.assertionId(), "1", "l".repeat(16),
                Map.of("preference", "stepwise"), null));

        var replay = repository.confirm(confirmation).detail();

        assertThat(replay.version()).isEqualTo("1");
        assertThat(replay.displayValue()).containsEntry("preference", "brief");
        assertThat(replay.confirmationStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void highPrivacyListsMinimizeDisplayValue() {
        var fixture = insertMemory("m".repeat(43), "HIGH");

        var page = repository.list(new MemoryManagementRepository.ListQuery(
                fixture.subjectHash(), null, null, null, null));

        assertThat(page.items()).singleElement().extracting(MemoryManagementRepository.MemorySummary::displayValue)
                .isEqualTo(Map.of());
    }

    @Test
    void sensitiveCorrectionWithoutAConfiguredExternalKeyFailsBeforeAnyPlaintextPersistence() {
        var fixture = insertMemory("n".repeat(43), "SENSITIVE");

        assertThatThrownBy(() -> repository.correct(new MemoryManagementRepository.Correction(
                fixture.subjectHash(), fixture.assertionId(), "1", "o".repeat(16),
                Map.of("preference", "step_by_step"), "no-key-sentinel")))
                .isInstanceOf(SecurityException.class);

        assertThat(count("memory_version")).isEqualTo(1);
        assertThat(count("interaction_event")).isZero();
        assertThat(jdbcClient.sql("SELECT COALESCE(value_json::text, '') FROM memory_version")
                .query(String.class).single()).doesNotContain("step_by_step", "no-key-sentinel");
    }

    @Test
    void sensitiveCorrectionStoresTheSourceEnvelopeOnlyAfterTheEventExists() {
        var masterKey = new byte[32];
        java.util.Arrays.fill(masterKey, (byte) 7);
        var protector = new PayloadProtector(new InMemoryTestKeyProvider(masterKey));
        var protectedRepository = new JdbcMemoryManagementRepository(
                jdbcClient, protector, new RetentionPolicy(), new JdbcInteractionPayloadKeyEnvelopeStore(jdbcClient));
        var fixture = insertMemory("o".repeat(43), "SENSITIVE", protector);

        protectedRepository.correct(new MemoryManagementRepository.Correction(
                fixture.subjectHash(), fixture.assertionId(), "1", "p".repeat(16),
                Map.of("preference", "step_by_step"), "encrypted-source"));

        assertThat(count("interaction_event")).isEqualTo(1);
        assertThat(count("interaction_payload_key_envelope")).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM interaction_payload_key_envelope envelope
                        JOIN interaction_event event
                          ON event.event_id = envelope.event_id
                         AND event.schema_version = envelope.schema_version
                        """).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void disabledSubjectAndTombstonedAssertionsAreBlockedForManagementReadsAndWrites() {
        var disabledSubject = insertMemory("p".repeat(43), "STANDARD");
        jdbcClient.sql("UPDATE learner_subject SET status = 'DISABLED' WHERE subject_hash = :subject")
                .param("subject", disabledSubject.subjectHash()).update();

        assertThat(repository.list(new MemoryManagementRepository.ListQuery(
                disabledSubject.subjectHash(), null, null, null, null)).items()).isEmpty();
        assertThat(repository.findDetail(disabledSubject.subjectHash(), disabledSubject.assertionId())).isEmpty();
        assertThatThrownBy(() -> repository.confirm(new MemoryManagementRepository.Mutation(
                disabledSubject.subjectHash(), disabledSubject.assertionId(), "1", "q".repeat(16))))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);

        var tombstoned = insertMemory("r".repeat(43), "STANDARD");
        var subjectId = jdbcClient.sql("SELECT learner_subject_id FROM learner_subject WHERE subject_hash = :subject")
                .param("subject", tombstoned.subjectHash()).query(UUID.class).single();
        jdbcClient.sql("INSERT INTO forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at) VALUES (:id,:subject,:assertion,'ASSERTION',:digest,'BLOCKED',:at)")
                .param("id", UUID.randomUUID()).param("subject", subjectId).param("assertion", tombstoned.assertionId())
                .param("digest", "1".repeat(64)).param("at", Timestamp.from(NOW)).update();
        jdbcClient.sql("INSERT INTO forget_tombstone (forget_tombstone_id, learner_subject_id, memory_assertion_id, scope, request_id, created_at) SELECT :id,:subject,:assertion,'ASSERTION',forget_request_id,:at FROM forget_request WHERE learner_subject_id=:subject AND memory_assertion_id=:assertion")
                .param("id", UUID.randomUUID()).param("subject", subjectId).param("assertion", tombstoned.assertionId()).param("at", Timestamp.from(NOW)).update();

        assertThat(repository.list(new MemoryManagementRepository.ListQuery(
                tombstoned.subjectHash(), null, null, null, null)).items()).isEmpty();
        assertThat(repository.findDetail(tombstoned.subjectHash(), tombstoned.assertionId())).isEmpty();
        assertThatThrownBy(() -> repository.correct(new MemoryManagementRepository.Correction(
                tombstoned.subjectHash(), tombstoned.assertionId(), "1", "s".repeat(16), Map.of("preference", "blocked"), null)))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);
    }

    @Test
    void managementReceiptsCannotReplayOrDecryptAfterSingleOrFullForget() {
        var single = insertMemory("t".repeat(43), "STANDARD");
        var singleAction = new MemoryManagementRepository.Mutation(
                single.subjectHash(), single.assertionId(), "1", "u".repeat(16));
        repository.disable(singleAction);
        forgetRepository.requestSingle(single.subjectHash(), single.assertionId(), "v".repeat(16), NOW);

        assertThatThrownBy(() -> repository.disable(singleAction))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);

        var full = insertMemory("w".repeat(43), "STANDARD");
        var fullAction = new MemoryManagementRepository.Mutation(
                full.subjectHash(), full.assertionId(), "1", "x".repeat(16));
        repository.disable(fullAction);
        forgetRepository.requestFull(full.subjectHash(), "y".repeat(16), NOW);

        assertThatThrownBy(() -> repository.disable(fullAction))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);
    }

    @Test
    void sensitiveReceiptReplayIsGatedBySingleForgetBeforeAnyDecrypt() {
        var masterKey = new byte[32];
        java.util.Arrays.fill(masterKey, (byte) 7);
        var protector = new PayloadProtector(new InMemoryTestKeyProvider(masterKey));
        var protectedRepository = new JdbcMemoryManagementRepository(jdbcClient, protector);
        var fixture = insertMemory("z".repeat(43), "SENSITIVE", protector);
        var action = new MemoryManagementRepository.Mutation(
                fixture.subjectHash(), fixture.assertionId(), "1", "0".repeat(16));

        assertThat(protectedRepository.disable(action).detail().displayValue())
                .containsEntry("preference", "brief");
        forgetRepository.requestSingle(fixture.subjectHash(), fixture.assertionId(), "1".repeat(16), NOW);

        assertThatThrownBy(() -> protectedRepository.disable(action))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);
    }

    private Fixture insertMemory(String subjectHash, String privacyClass) {
        return insertMemory(subjectHash, privacyClass, null);
    }

    private Fixture insertMemory(String subjectHash, String privacyClass, PayloadProtector protector) {
        var subjectId = UUID.randomUUID();
        var assertionId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var protectedPrivacy = "SENSITIVE".equals(privacyClass) || "HIGH".equals(privacyClass);
        var protectedValue = protectedPrivacy && protector != null
                ? protector.encrypt(new PayloadProtector.PayloadBinding(
                        subjectHash, versionId, MemoryCandidate.VALUE_SCHEMA_VERSION,
                        "memory_version", KeyPurpose.MEMORY_VERSION_VALUE),
                "{\"preference\":\"brief\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : null;
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:subjectId, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("subjectId", subjectId).param("subjectHash", subjectHash).param("createdAt", Timestamp.from(NOW)).update();
        jdbcClient.sql("""
                        INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at)
                        VALUES (:subjectId, 0, 1, :updatedAt)
                        """)
                .param("subjectId", subjectId).param("updatedAt", Timestamp.from(NOW)).update();
        jdbcClient.sql("""
                        INSERT INTO consent_policy_version (
                            consent_policy_version_id, learner_subject_id, revision, status, allowed_categories,
                            valid_from, valid_until, created_at)
                        VALUES (:policyId, :subjectId, 1, 'ACTIVE', CAST('[\"PREFERENCE\"]' AS jsonb),
                                :validFrom, null, :createdAt)
                        """)
                .param("policyId", UUID.randomUUID()).param("subjectId", subjectId)
                .param("validFrom", Timestamp.from(NOW.minusSeconds(60))).param("createdAt", Timestamp.from(NOW)).update();
        jdbcClient.sql("""
                        INSERT INTO memory_assertion (
                            memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at)
                        VALUES (:assertionId, :subjectId, 'preference-key', 'PREFERENCE', :createdAt)
                        """)
                .param("assertionId", assertionId).param("subjectId", subjectId).param("createdAt", Timestamp.from(NOW)).update();
        jdbcClient.sql("""
                        INSERT INTO memory_version (
                            memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                            status, value_json, valid_from, valid_until, recorded_at, recorded_until,
                            confidence, stability_score, privacy_level, consent_revision, created_at,
                            protected_value_ciphertext, protected_value_nonce, protected_value_key_reference,
                            protected_value_algorithm, protected_value_crypto_version)
                        VALUES (:versionId, :assertionId, :subjectId, 1, 'ACTIVE', CAST(:valueJson AS jsonb),
                                :validFrom, null, :recordedAt, null, 0.9, 0.8, :privacyClass, 1, :createdAt,
                                :ciphertext, :nonce, :keyReference, :algorithm, :cryptoVersion)
                        """)
                .param("versionId", versionId).param("assertionId", assertionId).param("subjectId", subjectId)
                .param("validFrom", Timestamp.from(NOW)).param("recordedAt", Timestamp.from(NOW))
                .param("privacyClass", privacyClass).param("createdAt", Timestamp.from(NOW))
                .param("valueJson", protectedPrivacy ? null : "{\"preference\":\"brief\"}")
                .param("ciphertext", protectedValue == null ? (protectedPrivacy ? new byte[16] : null) : protectedValue.ciphertextAndTag())
                .param("nonce", protectedValue == null ? (protectedPrivacy ? new byte[12] : null) : protectedValue.nonce())
                .param("keyReference", protectedValue == null ? (protectedPrivacy ? "k-" + "0".repeat(32) : null) : protectedValue.keyReference())
                .param("algorithm", protectedPrivacy ? EncryptedPayload.ALGORITHM : null)
                .param("cryptoVersion", protectedPrivacy ? EncryptedPayload.CRYPTO_VERSION : null)
                .update();
        jdbcClient.sql("""
                        INSERT INTO memory_head_projection (
                            memory_assertion_id, learner_subject_id, memory_version_id, status, memory_epoch, updated_at)
                        VALUES (:assertionId, :subjectId, :versionId, 'ACTIVE', 0, :updatedAt)
                        """)
                .param("assertionId", assertionId).param("subjectId", subjectId).param("versionId", versionId)
                .param("updatedAt", Timestamp.from(NOW)).update();
        return new Fixture(subjectHash, assertionId);
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long memoryEpoch() {
        return jdbcClient.sql("SELECT memory_epoch FROM learner_epoch").query(Long.class).single();
    }

    private List<String> transitions() {
        return jdbcClient.sql("SELECT transition_type || ':' || reason_code FROM memory_transition ORDER BY transitioned_at")
                .query(String.class).list();
    }

    private String outboxPayload() {
        return jdbcClient.sql("SELECT payload::text FROM transactional_outbox ORDER BY created_at LIMIT 1")
                .query(String.class).single();
    }

    private String receipt() {
        return jdbcClient.sql("SELECT action_type || ':' || idempotency_key_digest || ':' || request_digest FROM memory_management_action_receipt")
                .query(String.class).single();
    }

    private record Fixture(String subjectHash, UUID assertionId) {
    }
}
