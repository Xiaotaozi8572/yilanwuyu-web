package com.yilan.memory.adapter.redis;

import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.support.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisOutboxPublisherTest extends RedisIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final String SUBJECT_HASH = "subject-m3-sensitive";
    private static final String SENSITIVE_SOURCE = "source_text=我想让回答使用绝密昵称";

    @Autowired
    private RedisOutboxPublisher publisher;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanUp() {
        if (REDIS.isRunning()) {
            redisTemplate.delete(RedisStreamNames.events());
        }
        jdbcClient.sql("TRUNCATE TABLE transactional_outbox, learner_subject CASCADE").update();
    }

    @Test
    @Order(1)
    void publishesOnlyOpaqueIdentifiersAndMarksOutboxAfterRedisAck() {
        var outboxId = insertPendingOutboxWithSensitiveSource();

        assertThat(publisher.publishBatch(10)).isEqualTo(1);

        MapRecord<String, Object, Object> record = firstRecord();
        assertThat(record.getValue().keySet()).containsExactlyInAnyOrder(
                "schema_version", "outbox_id", "event_id", "routing_hash", "traceparent");
        assertThat(record.getValue().toString())
                .doesNotContain("query", "answer", "source_text", SENSITIVE_SOURCE, SUBJECT_HASH);
        assertThat(record.getValue().get("outbox_id")).isEqualTo(outboxId.toString());
        assertThat(record.getValue().get("event_id")).isEqualTo(eventIdFor(outboxId).toString());
        assertThat(record.getValue().get("routing_hash")).isNotEqualTo(SUBJECT_HASH);
        assertThat(outboxStatus(outboxId)).isEqualTo("PUBLISHED");
    }

    @Test
    @Order(2)
    void redisFailureLeavesPostgresOutboxPending() {
        var outboxId = insertPendingOutboxWithSensitiveSource();
        REDIS.stop();

        assertThat(publisher.publishBatch(10)).isZero();

        assertThat(outboxStatus(outboxId)).isEqualTo("PENDING");
    }

    @Test
    void rejectsInvalidStreamNamespaceAndBatchSize() {
        assertThatThrownBy(() -> RedisStreamNames.requireSupportedPrefix("memory:v2:"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisOutboxPublisher.PublisherSettings(0, java.time.Duration.ofMinutes(1),
                java.time.Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisOutboxPublisher.PublisherSettings(501, java.time.Duration.ofMinutes(1),
                java.time.Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MapRecord<String, Object, Object> firstRecord() {
        return redisTemplate.opsForStream()
                .range(RedisStreamNames.events(), Range.unbounded())
                .getFirst();
    }

    private UUID insertPendingOutboxWithSensitiveSource() {
        var learnerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        var protectedPayload = EncryptedPayload.parse(testProtector().seal(
                new PayloadProtector.SourceMaterialBinding(
                        SUBJECT_HASH, eventId, "v1", "interaction_event", KeyPurpose.INTERACTION_SOURCE),
                SENSITIVE_SOURCE.getBytes(StandardCharsets.UTF_8)));
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:learnerId, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("learnerId", learnerId)
                .param("subjectHash", SUBJECT_HASH)
                .param("createdAt", Timestamp.from(NOW))
                .update();
        jdbcClient.sql("""
                        INSERT INTO interaction_event (
                            event_id, schema_version, learner_subject_id, session_id, event_type, source_kind,
                            occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext,
                            payload_digest, trace_id, payload_key_reference, payload_nonce, payload_algorithm,
                            payload_crypto_version)
                        VALUES (
                            :eventId, 'v1', :learnerId, 'session-m3', 'PREFERENCE', 'GENERAL',
                            :occurredAt, :receivedAt, 'SENSITIVE', 1, :payloadCiphertext,
                            :payloadDigest, '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
                            :keyReference, :nonce, :algorithm, :cryptoVersion)
                        """)
                .param("eventId", eventId)
                .param("learnerId", learnerId)
                .param("occurredAt", Timestamp.from(NOW))
                .param("receivedAt", Timestamp.from(NOW))
                .param("payloadCiphertext", protectedPayload.serialize())
                .param("payloadDigest", "a".repeat(64))
                .param("keyReference", protectedPayload.keyReference())
                .param("nonce", protectedPayload.nonce())
                .param("algorithm", EncryptedPayload.ALGORITHM)
                .param("cryptoVersion", EncryptedPayload.CRYPTO_VERSION)
                .update();
        jdbcClient.sql("""
                        INSERT INTO transactional_outbox (
                            outbox_id, aggregate_id, event_type, payload, created_at)
                        VALUES (
                            :outboxId, :eventId, 'INTERACTION_EVENT_ACCEPTED',
                            CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("outboxId", outboxId)
                .param("eventId", eventId)
                .param("payload", "{\"event_schema_version\":\"v1\",\"source_text\":\""
                        + SENSITIVE_SOURCE + "\"}")
                .param("createdAt", Timestamp.from(NOW))
                .update();
        return outboxId;
    }

    private UUID eventIdFor(UUID outboxId) {
        return jdbcClient.sql("SELECT aggregate_id FROM transactional_outbox WHERE outbox_id = :outboxId")
                .param("outboxId", outboxId)
                .query(UUID.class)
                .single();
    }

    private String outboxStatus(UUID outboxId) {
        return jdbcClient.sql("""
                        SELECT CASE WHEN published_at IS NULL THEN 'PENDING' ELSE 'PUBLISHED' END
                        FROM transactional_outbox
                        WHERE outbox_id = :outboxId
                        """)
                .param("outboxId", outboxId)
                .query(String.class)
                .single();
    }

    private static PayloadProtector testProtector() {
        var masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        return new PayloadProtector(new InMemoryTestKeyProvider(masterKey));
    }
}
