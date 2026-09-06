package com.yilan.memory.application.privacy;

import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.adapter.postgres.JdbcMemoryManagementRepository;
import com.yilan.memory.application.management.MemoryManagementRepository;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class EncryptedRepositoryTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final String SUBJECT_HASH = "q".repeat(43);
    private static final byte[] TEST_MASTER_KEY = testMasterKey();

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }

    @Test
    void sensitiveCorrectionStoresOnlyAesGcmEnvelopeAndDecryptsOnlyInTheAuthorityAdapter() {
        var fixture = insertSensitiveMemory();
        var repository = new JdbcMemoryManagementRepository(
                jdbcClient, testProtector());

        var result = repository.correct(new MemoryManagementRepository.Correction(
                SUBJECT_HASH, fixture.assertionId(), "1", "a".repeat(16),
                Map.of("preference", "step_by_step"), "protected correction"));

        assertThat(result.detail().displayValue()).containsEntry("preference", "step_by_step");
        var stored = jdbcClient.sql("""
                        SELECT value_json::text, protected_value_ciphertext, protected_value_nonce,
                               protected_value_key_reference, protected_value_algorithm, protected_value_crypto_version
                        FROM memory_version WHERE memory_assertion_id = :assertionId
                        ORDER BY version_sequence DESC LIMIT 1
                        """)
                .param("assertionId", fixture.assertionId())
                .query((rows, ignored) -> new StoredVersion(
                        rows.getString("value_json"), rows.getBytes("protected_value_ciphertext"),
                        rows.getBytes("protected_value_nonce"), rows.getString("protected_value_key_reference"),
                        rows.getString("protected_value_algorithm"), rows.getInt("protected_value_crypto_version")))
                .list();
        var version = stored.getLast();
        assertThat(version.valueJson()).isNull();
        assertThat(contains(version.ciphertext(), "step_by_step".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(version.nonce()).hasSize(12);
        assertThat(version.keyReference()).matches("k-[0-9a-f]{32}");
        assertThat(version.algorithm()).isEqualTo("AES-256-GCM");
        assertThat(version.cryptoVersion()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COALESCE(string_agg(value_json::text, ''), '') FROM memory_version")
                .query(String.class).single()).doesNotContain("step_by_step", "protected correction");
    }

    private Fixture insertSensitiveMemory() {
        var subjectId = UUID.randomUUID();
        var assertionId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var protectedValue = testProtector().encrypt(new PayloadProtector.PayloadBinding(
                SUBJECT_HASH, versionId, "memory-candidate/v1", "memory_version",
                KeyPurpose.MEMORY_VERSION_VALUE), "{\"preference\":\"brief\"}".getBytes(StandardCharsets.UTF_8));
        jdbcClient.sql("INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at) VALUES (:subjectId, :subjectHash, 'ACTIVE', :now)")
                .param("subjectId", subjectId).param("subjectHash", SUBJECT_HASH).param("now", Timestamp.from(NOW)).update();
        jdbcClient.sql("INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at) VALUES (:subjectId, 0, 1, :now)")
                .param("subjectId", subjectId).param("now", Timestamp.from(NOW)).update();
        jdbcClient.sql("""
                        INSERT INTO consent_policy_version (consent_policy_version_id, learner_subject_id, revision, status,
                            allowed_categories, valid_from, valid_until, created_at)
                        VALUES (:policyId, :subjectId, 1, 'ACTIVE', CAST('[\"PREFERENCE\"]' AS jsonb), :now, null, :now)
                        """)
                .param("policyId", UUID.randomUUID()).param("subjectId", subjectId).param("now", Timestamp.from(NOW)).update();
        jdbcClient.sql("INSERT INTO memory_assertion (memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at) VALUES (:assertionId, :subjectId, 'preference-key', 'PREFERENCE', :now)")
                .param("assertionId", assertionId).param("subjectId", subjectId).param("now", Timestamp.from(NOW)).update();
        jdbcClient.sql("""
                        INSERT INTO memory_version (memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                            status, value_json, valid_from, valid_until, recorded_at, recorded_until, confidence,
                            stability_score, privacy_level, consent_revision, created_at, protected_value_ciphertext,
                            protected_value_nonce, protected_value_key_reference, protected_value_algorithm,
                            protected_value_crypto_version)
                        VALUES (:versionId, :assertionId, :subjectId, 1, 'ACTIVE', NULL,
                            :now, null, :now, null, 0.9, 0.8, 'SENSITIVE', 1, :now, :ciphertext, :nonce,
                            :keyReference, :algorithm, :cryptoVersion)
                        """)
                .param("versionId", versionId).param("assertionId", assertionId).param("subjectId", subjectId)
                .param("now", Timestamp.from(NOW)).param("ciphertext", protectedValue.ciphertextAndTag())
                .param("nonce", protectedValue.nonce()).param("keyReference", protectedValue.keyReference())
                .param("algorithm", EncryptedPayload.ALGORITHM).param("cryptoVersion", EncryptedPayload.CRYPTO_VERSION)
                .update();
        jdbcClient.sql("""
                        INSERT INTO memory_head_projection (memory_assertion_id, learner_subject_id, memory_version_id, status, memory_epoch, updated_at)
                        VALUES (:assertionId, :subjectId, :versionId, 'ACTIVE', 0, :now)
                        """)
                .param("assertionId", assertionId).param("subjectId", subjectId).param("versionId", versionId).param("now", Timestamp.from(NOW)).update();
        return new Fixture(assertionId);
    }

    private record Fixture(UUID assertionId) { }

    private static PayloadProtector testProtector() {
        return new PayloadProtector(new InMemoryTestKeyProvider(TEST_MASTER_KEY));
    }

    private static byte[] testMasterKey() {
        var key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return key;
    }

    private record StoredVersion(
            String valueJson, byte[] ciphertext, byte[] nonce, String keyReference, String algorithm, int cryptoVersion) { }

    private static boolean contains(byte[] value, byte[] needle) {
        for (var offset = 0; offset <= value.length - needle.length; offset++) {
            var matches = true;
            for (var index = 0; index < needle.length; index++) {
                if (value[offset + index] != needle[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
