package com.yilan.memory.application.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.adapter.redis.RedisStreamNames;
import com.yilan.memory.adapter.redis.MemoryEventConsumer;
import com.yilan.memory.adapter.redis.OutboxReconciler;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.support.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AsyncRecoveryTest extends RedisIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] TEST_MASTER_KEY = testMasterKey();
    private static final Instant NOW = Instant.parse("2026-07-20T15:00:00Z");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(RedisStreamNames.events());
        redisTemplate.delete(RedisStreamNames.deadLetter());
        jdbcClient.sql("""
                        TRUNCATE TABLE memory_audit_event, async_processing_checkpoint,
                            transactional_outbox, interaction_event, learner_subject CASCADE
                        """).update();
    }

    @Test
    void reconciliationRestoresIdentifierEnvelopeAfterRedisFlushAndGroupDeletion() throws Exception {
        var event = insertPublishedOutbox();
        var reconciler = new OutboxReconciler(jdbcClient, redisTemplate);

        assertThat(reconciler.requeueMissingCompletions(NOW, 10)).isEqualTo(1);
        assertThat(redisTemplate.opsForStream().range(RedisStreamNames.events(), Range.unbounded())).hasSize(1);

        redisTemplate.delete(RedisStreamNames.events());

        assertThat(reconciler.requeueMissingCompletions(NOW.plusSeconds(1), 10)).isEqualTo(1);
        var records = redisTemplate.opsForStream().range(RedisStreamNames.events(), Range.unbounded());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getValue().keySet()).containsExactlyInAnyOrder(
                "schema_version", "outbox_id", "event_id", "routing_hash", "traceparent");
        assertThat(records.getFirst().getValue().toString()).doesNotContain("private source", event.learnerId().toString());
        assertThat(jdbcClient.sql("SELECT count(*) FROM memory_audit_event WHERE audit_type = 'ASYNC_RECONCILIATION_REQUEUED'")
                .query(Integer.class).single()).isEqualTo(2);
        var metadata = jdbcClient.sql("""
                        SELECT redacted_metadata::text
                        FROM memory_audit_event
                        WHERE audit_type = 'ASYNC_RECONCILIATION_REQUEUED'
                          AND created_at = :createdAt
                        """)
                .param("createdAt", Timestamp.from(NOW.plusSeconds(1)))
                .query(String.class)
                .single();
        var metadataJson = JSON.readTree(metadata);
        var metadataKeys = new HashSet<String>();
        metadataJson.fieldNames().forEachRemaining(metadataKeys::add);

        assertThat(metadataJson.isObject()).isTrue();
        assertThat(metadataKeys).isEqualTo(Set.of(
                "outbox_id", "stage_name", "stage_version", "outcome", "diagnostic_code"));
        assertThat(metadataJson.path("outbox_id").asText()).isEqualTo(event.outboxId().toString());
        assertThat(metadataJson.path("stage_name").asText()).isEqualTo("CANDIDATE_EXTRACTION");
        assertThat(metadataJson.path("stage_version").asInt()).isEqualTo(1);
        assertThat(metadataJson.path("outcome").asText()).isEqualTo("REQUEUED");
        assertThat(metadataJson.path("diagnostic_code").asText()).isEqualTo("MISSING_SUCCESS_CHECKPOINT");
        assertThat(metadata).doesNotContain(
                "private source", event.learnerId().toString(), "subject-" + event.learnerId());
    }

    @Test
    void reconciliationDoesNotLetV1SuccessSuppressV2OfTheSameEvent() {
        var v1 = insertPublishedOutbox();
        var v2 = insertSchemaVariant(v1, "v2");
        var checkpoints = new CheckpointService(jdbcClient, transactionManager);
        checkpoints.transactions().executeWithoutResult(ignored -> {
            checkpoints.claim(v1.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1,
                    v1.learnerId(), v1.outboxId(), NOW);
            checkpoints.succeed(v1.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1, NOW);
        });

        assertThat(new OutboxReconciler(jdbcClient, redisTemplate).requeueMissingCompletions(NOW, 10)).isEqualTo(1);
        var records = redisTemplate.opsForStream().range(RedisStreamNames.events(), Range.unbounded());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getValue()).containsEntry("outbox_id", v2.outboxId().toString());
    }

    @Test
    void consumerRecreatesDestroyedGroupAndProcessesExistingStreamRecord() {
        var event = insertPublishedOutbox();
        var group = "destroyed-" + UUID.randomUUID();
        assertThat(new OutboxReconciler(jdbcClient, redisTemplate).requeueMissingCompletions(NOW, 10)).isEqualTo(1);
        createGroup(group);
        destroyGroup(group);
        assertThat(redisTemplate.opsForStream().range(RedisStreamNames.events(), Range.unbounded())).hasSize(1);
        var calls = new AtomicInteger();
        var consumer = new MemoryEventConsumer(
                jdbcClient,
                redisTemplate,
                new CheckpointService(jdbcClient, transactionManager),
                new AsyncStageProcessor() {
                    @Override
                    public PreparedAttempt prepareOutsideTransaction(
                            EventContext context) {
                        calls.incrementAndGet();
                        return new TestAttempt();
                    }

                    @Override
                    public CandidateProcessingService.StageOutcome finalizeInTransaction(
                            EventContext context,
                            PreparedAttempt attempt) {
                        return new CandidateProcessingService.StageOutcome.Success();
                    }
                },
                group,
                "consumer-" + UUID.randomUUID(),
                10,
                Duration.ZERO,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        assertThat(consumer.drainOnce()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    private void createGroup(String group) {
        redisTemplate.opsForStream().createGroup(
                RedisStreamNames.events(), ReadOffset.from("0-0"), group);
    }

    private void destroyGroup(String group) {
        redisTemplate.opsForStream().destroyGroup(RedisStreamNames.events(), group);
    }

    private EventFixture insertPublishedOutbox() {
        var learnerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:learnerId, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("learnerId", learnerId)
                .param("subjectHash", "subject-" + learnerId)
                .param("createdAt", Timestamp.from(NOW))
                .update();
        return insertSchemaVariant(new EventFixture(eventId, learnerId, null), "v1");
    }

    private EventFixture insertSchemaVariant(EventFixture existing, String schemaVersion) {
        var outboxId = UUID.randomUUID();
        var protectedPayload = EncryptedPayload.parse(testProtector().seal(
                new PayloadProtector.SourceMaterialBinding(
                        "subject-" + existing.learnerId(), existing.eventId(), schemaVersion,
                        "interaction_event", KeyPurpose.INTERACTION_SOURCE),
                ("private source " + schemaVersion).getBytes(StandardCharsets.UTF_8)));
        jdbcClient.sql("""
                        INSERT INTO interaction_event (
                            event_id, schema_version, learner_subject_id, session_id, event_type, source_kind,
                            occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext,
                            payload_digest, trace_id, payload_key_reference, payload_nonce, payload_algorithm,
                            payload_crypto_version)
                        VALUES (
                            :eventId, :schemaVersion, :learnerId, 'session-m3', 'PREFERENCE', 'GENERAL',
                            :occurredAt, :receivedAt, 'SENSITIVE', 1, :payloadCiphertext,
                            :payloadDigest, '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
                            :keyReference, :nonce, :algorithm, :cryptoVersion)
                        """)
                .param("eventId", existing.eventId())
                .param("schemaVersion", schemaVersion)
                .param("learnerId", existing.learnerId())
                .param("occurredAt", Timestamp.from(NOW))
                .param("receivedAt", Timestamp.from(NOW))
                .param("payloadCiphertext", protectedPayload.serialize())
                .param("payloadDigest", (schemaVersion.equals("v1") ? "d" : "e").repeat(64))
                .param("keyReference", protectedPayload.keyReference())
                .param("nonce", protectedPayload.nonce())
                .param("algorithm", EncryptedPayload.ALGORITHM)
                .param("cryptoVersion", EncryptedPayload.CRYPTO_VERSION)
                .update();
        jdbcClient.sql("""
                        INSERT INTO transactional_outbox (outbox_id, aggregate_id, event_type, payload, created_at, published_at)
                        VALUES (:outboxId, :eventId, 'INTERACTION_EVENT_ACCEPTED', CAST(:payload AS jsonb), :createdAt, :publishedAt)
                        """)
                .param("outboxId", outboxId)
                .param("eventId", existing.eventId())
                .param("payload", "{\"event_id\":\"" + existing.eventId()
                        + "\",\"event_schema_version\":\"" + schemaVersion + "\"}")
                .param("createdAt", Timestamp.from(NOW))
                .param("publishedAt", Timestamp.from(NOW))
                .update();
        return new EventFixture(existing.eventId(), existing.learnerId(), outboxId);
    }

    private record EventFixture(UUID eventId, UUID learnerId, UUID outboxId) {
    }

    private record TestAttempt() implements AsyncStageProcessor.PreparedAttempt {
    }

    private static PayloadProtector testProtector() {
        return new PayloadProtector(new InMemoryTestKeyProvider(TEST_MASTER_KEY));
    }

    private static byte[] testMasterKey() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
