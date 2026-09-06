package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.consent.ConsentService;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.security.MemoryRole;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JdbcConsentRepositoryTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Autowired
    private JdbcConsentRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcForgetRepository forgetRepository;

    @Autowired
    private ConsentService consentService;

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }

    @Test
    void atomicallyAppendsVersionAdvancesAuthorityEpochAndQueuesIdentifierOnlyPurge() {
        var subject = "consent-subject-a";
        insertLearner(subject);
        var policy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE), "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER);

        var commit = repository.append(subject, 0, policy);

        assertThat(commit.revision()).isEqualTo(1);
        assertThat(commit.consentEpoch()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT purpose_text_version, source_capture_enabled, retention_days, actor_role
                        FROM consent_policy_version
                        """)
                .query((resultSet, rowNumber) -> new ControlRow(
                        resultSet.getString("purpose_text_version"),
                        resultSet.getBoolean("source_capture_enabled"),
                        resultSet.getInt("retention_days"),
                        resultSet.getString("actor_role")))
                .single())
                .isEqualTo(new ControlRow("memory-consent-v1", true, 14, "LEARNER"));
        assertThat(jdbcClient.sql("SELECT consent_epoch FROM learner_epoch").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT event_type, payload::text FROM transactional_outbox")
                .query((resultSet, rowNumber) -> resultSet.getString("event_type") + ":" + resultSet.getString("payload"))
                .single())
                .contains("CONSENT_PROJECTION_PURGE", "consent_epoch")
                .doesNotContain(subject, "payload_ciphertext", "source");
    }

    @Test
    void appendCreatesActivePseudonymousSubjectWhenItDoesNotExistYet() {
        var subject = "consent-subject-first-opt-in";
        var policy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE), "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER);

        var commit = repository.append(subject, 0, policy);

        assertThat(commit.revision()).isEqualTo(1);
        assertThat(commit.consentEpoch()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT status FROM learner_subject WHERE subject_hash = :subjectHash")
                .param("subjectHash", subject)
                .query(String.class)
                .single()).isEqualTo("ACTIVE");
        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_policy_version").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void staleRevisionDoesNotAppendAnotherPolicyOrAdvanceEpoch() {
        var subject = "consent-subject-b";
        insertLearner(subject);
        var policy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE), "memory-consent-v1", true, null, NOW, MemoryRole.LEARNER);
        repository.append(subject, 0, policy);

        assertThatThrownBy(() -> repository.append(subject, 0, policy))
                .isInstanceOf(ConsentService.StaleConsentVersionException.class);

        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_policy_version").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT consent_epoch FROM learner_epoch").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM transactional_outbox").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void fullForgetMakesConsentAppendFailClosedWithoutWritingPolicyEpochOrOutbox() {
        var subject = "consent-subject-forgotten";
        insertLearner(subject);
        forgetRepository.requestFull(subject, "i".repeat(16), NOW);
        var policy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE), "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER);

        assertThatThrownBy(() -> repository.append(subject, 0, policy))
                .isInstanceOf(JdbcConsentRepository.UnknownSubjectException.class);
        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_policy_version").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT consent_epoch FROM learner_epoch").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM transactional_outbox").query(Long.class).single()).isZero();
    }

    @Test
    void fullForgetHidesHistoricalConsentFromCurrentRepositoryAndServiceViews() {
        var subject = "consent-subject-forgotten-current";
        insertLearner(subject);
        var policy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE), "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER);
        repository.append(subject, 0, policy);

        forgetRepository.requestFull(subject, "i".repeat(16), NOW);

        assertThat(repository.find(subject)).isEmpty();
        var view = consentService.current(subject);
        assertThat(view.version()).isEqualTo("0");
        assertThat(view.longTermEnabled()).isFalse();
        assertThat(view.allowedCategories()).isEmpty();
        assertThat(view.effectiveAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void sameKeyAndFiniteRequestReplaysOriginalPolicyWithoutAnotherAuthorityWrite() {
        var subject = "consent-subject-idempotent";
        var key = "consent-idempotency-key-000000000001";
        insertLearner(subject);
        var firstPolicy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.MASTERY, MemoryCategory.PREFERENCE),
                "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER);
        var retryPolicy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE, MemoryCategory.MASTERY),
                "memory-consent-v1", true, 14, NOW.plus(1, ChronoUnit.DAYS), MemoryRole.LEARNER);

        var first = repository.append(subject, 0, firstPolicy, key);
        var replay = repository.append(subject, 0, retryPolicy, key);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result().revision()).isEqualTo(1);
        assertThat(replay.result().effectiveAt()).isEqualTo(NOW);
        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_policy_version").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT consent_epoch FROM learner_epoch").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM transactional_outbox").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_action_receipt").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyOrIfMatchConflictsBeforeStaleVersion() {
        var subject = "consent-subject-idempotency-conflict";
        var key = "consent-idempotency-key-000000000002";
        insertLearner(subject);
        var policy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE), "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER);
        repository.append(subject, 0, policy, key);

        assertThatThrownBy(() -> repository.append(subject, 1, policy, key))
                .isInstanceOf(ConsentService.IdempotencyConflictException.class);
        assertThatThrownBy(() -> repository.append(subject, 0, new ConsentService.Policy(
                true, Set.of(MemoryCategory.MASTERY), "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER), key))
                .isInstanceOf(ConsentService.IdempotencyConflictException.class);
        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_policy_version").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_action_receipt").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void receiptPersistsOnlyDigestsAndNeverTheRawIdempotencyKey() {
        var subject = "consent-subject-receipt-digest";
        var rawKey = "raw-consent-idempotency-key-never-persisted-0003";
        insertLearner(subject);
        var policy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE), "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER);

        repository.append(subject, 0, policy, rawKey);

        var receipt = jdbcClient.sql("""
                        SELECT idempotency_key_digest, request_digest, result_policy_revision
                        FROM consent_action_receipt
                        """)
                .query((resultSet, rowNumber) -> new ReceiptRow(
                        resultSet.getString("idempotency_key_digest"),
                        resultSet.getString("request_digest"),
                        resultSet.getLong("result_policy_revision")))
                .single();
        assertThat(receipt.idempotencyKeyDigest()).matches("[0-9a-f]{64}").doesNotContain(rawKey);
        assertThat(receipt.requestDigest()).matches("[0-9a-f]{64}").doesNotContain(rawKey);
        assertThat(receipt.resultPolicyRevision()).isEqualTo(1);
    }

    @Test
    void fullForgetPreventsHistoricalConsentReceiptReplay() {
        var subject = "consent-subject-replay-after-forget";
        var key = "consent-idempotency-key-000000000004";
        insertLearner(subject);
        var policy = new ConsentService.Policy(
                true, Set.of(MemoryCategory.PREFERENCE), "memory-consent-v1", true, 14, NOW, MemoryRole.LEARNER);
        repository.append(subject, 0, policy, key);
        forgetRepository.requestFull(subject, "f".repeat(16), NOW);
        var policiesBeforeReplay = jdbcClient.sql("SELECT count(*) FROM consent_policy_version").query(Long.class).single();
        var receiptsBeforeReplay = jdbcClient.sql("SELECT count(*) FROM consent_action_receipt").query(Long.class).single();
        var outboxBeforeReplay = jdbcClient.sql("SELECT count(*) FROM transactional_outbox").query(Long.class).single();

        assertThatThrownBy(() -> repository.append(subject, 0, policy, key))
                .isInstanceOf(JdbcConsentRepository.UnknownSubjectException.class);
        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_policy_version").query(Long.class).single())
                .isEqualTo(policiesBeforeReplay);
        assertThat(jdbcClient.sql("SELECT count(*) FROM consent_action_receipt").query(Long.class).single())
                .isEqualTo(receiptsBeforeReplay);
        assertThat(jdbcClient.sql("SELECT count(*) FROM transactional_outbox").query(Long.class).single())
                .isEqualTo(outboxBeforeReplay);
    }

    private void insertLearner(String subjectHash) {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:learnerSubjectId, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("learnerSubjectId", UUID.randomUUID())
                .param("subjectHash", subjectHash)
                .param("createdAt", Timestamp.from(NOW))
                .update();
    }

    private record ControlRow(String purposeTextVersion, boolean sourceCaptureEnabled, int retentionDays, String actorRole) {
    }

    private record ReceiptRow(String idempotencyKeyDigest, String requestDigest, long resultPolicyRevision) {
    }
}
