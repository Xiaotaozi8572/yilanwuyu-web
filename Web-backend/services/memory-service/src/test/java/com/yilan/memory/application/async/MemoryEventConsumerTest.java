package com.yilan.memory.application.async;

import com.yilan.memory.adapter.redis.RedisStreamNames;
import com.yilan.memory.adapter.redis.StreamEnvelope;
import com.yilan.memory.adapter.redis.MemoryEventConsumer;
import com.yilan.memory.adapter.redis.OutboxReconciler;
import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.adapter.postgres.JdbcCandidateRepository;
import com.yilan.memory.adapter.postgres.JdbcConsentRepository;
import com.yilan.memory.adapter.postgres.JdbcMemoryHistoryRepository;
import com.yilan.memory.adapter.worker.CandidateWorkerClient;
import com.yilan.memory.adapter.worker.WorkerProposalValidator;
import com.yilan.memory.application.governance.GovernCandidateUseCase;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.governance.PromotionRule;
import com.yilan.memory.support.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class MemoryEventConsumerTest extends RedisIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T14:00:00Z");
    private static final byte[] TEST_MASTER_KEY = testMasterKey();

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private CheckpointService checkpoints;

    @BeforeEach
    void setUp() {
        checkpoints = new CheckpointService(jdbcClient, transactionManager);
    }

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
    void duplicateRedisDeliveryRunsStageOnce() {
        var event = insertEvent();
        publish(event.eventId(), event.outboxId());
        publish(event.eventId(), event.outboxId());
        var calls = new AtomicInteger();
        var consumer = consumer("duplicate", NOW, terminalProcessor(calls, new CandidateProcessingService.StageOutcome.Success()));

        assertThat(consumer.drainOnce()).isEqualTo(2);

        assertThat(calls).hasValue(1);
        assertThat(checkpoints.count(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1)).isEqualTo(1);
        assertThat(checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow().status())
                .isEqualTo(CheckpointService.Status.SUCCEEDED);
    }

    @Test
    void sameEventIdWithDifferentTrustedSchemasRunsEachStageOnce() {
        var v1 = insertEvent();
        var v2 = insertSchemaVariant(v1, "v2");
        publish(v1.eventId(), v1.outboxId());
        publish(v2.eventId(), v2.outboxId());
        var calls = new AtomicInteger();
        var consumer = consumer("schema-qualified-" + UUID.randomUUID(), NOW,
                terminalProcessor(calls, new CandidateProcessingService.StageOutcome.Success()));

        assertThat(consumer.drainOnce()).isEqualTo(2);

        assertThat(calls).hasValue(2);
        assertThat(checkpoints.count(v1.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1)).isEqualTo(1);
        assertThat(checkpoints.count(v2.eventId(), "v2", StageName.CANDIDATE_EXTRACTION, 1)).isEqualTo(1);
        assertThat(checkpoints.find(v1.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow().status())
                .isEqualTo(CheckpointService.Status.SUCCEEDED);
        assertThat(checkpoints.find(v2.eventId(), "v2", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow().status())
                .isEqualTo(CheckpointService.Status.SUCCEEDED);

        publish(v2.eventId(), v2.outboxId());

        assertThat(consumer.drainOnce()).isOne();
        assertThat(calls).hasValue(2);
    }

    @Test
    void restartReclaimsPendingDeliveryAndCompletesAfterBackoff() throws InterruptedException {
        var event = insertEvent();
        publish(event.eventId(), event.outboxId());
        var attempts = new AtomicInteger();
        var group = "restart-" + UUID.randomUUID();
        var first = consumer(group, NOW, retryingProcessor(attempts, "PROCESSING_FAILURE"));

        assertThat(first.drainOnce()).isZero();
        assertThat(checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow().status())
                .isEqualTo(CheckpointService.Status.RETRYABLE);
        Thread.sleep(10L);

        var restarted = consumer(group, NOW.plusSeconds(3), terminalProcessor(attempts,
                new CandidateProcessingService.StageOutcome.Success()));
        assertThat(restarted.drainOnce()).isEqualTo(1);

        assertThat(attempts).hasValue(2);
        assertThat(checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow().status())
                .isEqualTo(CheckpointService.Status.SUCCEEDED);
    }

    @Test
    void poisonMessageMovesToDlqAfterFiveAttemptsAndRemainsReconciliable() throws InterruptedException {
        var event = insertEvent();
        publish(event.eventId(), event.outboxId());
        var group = "poison-" + UUID.randomUUID();
        var calls = new AtomicInteger();

        for (int attempt = 0; attempt < RetryPolicy.MAX_ATTEMPTS; attempt++) {
            var now = NOW.plusSeconds(100L * attempt);
            var consumer = consumer(group, now, retryingProcessor(calls, "PROCESSING_FAILURE"));
            assertThat(consumer.drainOnce()).isEqualTo(
                    attempt == RetryPolicy.MAX_ATTEMPTS - 1 ? 1 : 0);
            Thread.sleep(10L);
        }

        var checkpoint = checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow();
        assertThat(calls).hasValue(RetryPolicy.MAX_ATTEMPTS);
        assertThat(checkpoint.status()).isEqualTo(CheckpointService.Status.DEAD_LETTER);
        assertThat(checkpoint.attempts()).isEqualTo(RetryPolicy.MAX_ATTEMPTS);
        assertThat(redisTemplate.opsForStream().size(RedisStreamNames.deadLetter())).isEqualTo(1);
        assertThat(new OutboxReconciler(jdbcClient, redisTemplate).requeueMissingCompletions(NOW.plusSeconds(120), 10))
                .isEqualTo(1);
    }

    @Test
    void obsoleteSubjectAcksDuplicateDeliveryWithoutWritingDlq() {
        var event = insertEvent();
        jdbcClient.sql("UPDATE learner_subject SET status = 'DISABLED' WHERE learner_subject_id = :learnerId")
                .param("learnerId", event.learnerId())
                .update();
        publish(event.eventId(), event.outboxId());
        publish(event.eventId(), event.outboxId());
        var calls = new AtomicInteger();

        assertThat(consumer("obsolete", NOW, terminalProcessor(calls,
                new CandidateProcessingService.StageOutcome.Success())).drainOnce()).isEqualTo(2);

        assertThat(calls).hasValue(0);
        assertThat(checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow().status())
                .isEqualTo(CheckpointService.Status.OBSOLETE);
        assertThat(redisTemplate.opsForStream().size(RedisStreamNames.deadLetter())).isZero();
    }

    @Test
    void unknownDiagnosticCodeFallsBackToFixedRedactedCode() {
        var event = insertEvent();

        checkpoints.transactions().executeWithoutResult(ignored -> {
            var claim = checkpoints.claim(
                    event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1,
                    event.learnerId(), UUID.randomUUID(), NOW);
            checkpoints.fail(
                    event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1,
                    claim.checkpoint().attempts(), "UNREVIEWED_PROVIDER_DETAIL", NOW);
        });

        assertThat(checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow()
                .lastDiagnosticCode()).isEqualTo("UNCLASSIFIED_FAILURE");
    }

    @Test
    void workerPreparationRunsOutsideDatabaseTransactionAndLearnerLock() {
        var event = insertEvent();
        publish(event.eventId(), event.outboxId());
        var observedTransaction = new AtomicBoolean(true);
        var observedLock = new AtomicBoolean(true);
        var processor = new AsyncStageProcessor() {
            @Override
            public PreparedAttempt prepareOutsideTransaction(EventContext context) {
                observedTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                observedLock.set(TransactionSynchronizationManager.isSynchronizationActive());
                return new TestAttempt();
            }

            @Override
            public CandidateProcessingService.StageOutcome finalizeInTransaction(
                    EventContext context,
                    PreparedAttempt attempt) {
                return new CandidateProcessingService.StageOutcome.Success();
            }
        };

        assertThat(consumer("outside-transaction", NOW, processor).drainOnce()).isOne();

        assertThat(observedTransaction).isFalse();
        assertThat(observedLock).isFalse();
    }

    @Test
    void retryableResultStaysPendingUntilFifthAttemptThenDlqsAndAcks() throws InterruptedException {
        var event = insertEvent();
        publish(event.eventId(), event.outboxId());
        var calls = new AtomicInteger();
        var group = "retryable-" + UUID.randomUUID();

        for (int attempt = 0; attempt < RetryPolicy.MAX_ATTEMPTS; attempt++) {
            var current = consumer(group, NOW.plusSeconds(100L * attempt),
                    retryingProcessor(calls, "WORKER_UNAVAILABLE"));
            assertThat(current.drainOnce()).isEqualTo(attempt == RetryPolicy.MAX_ATTEMPTS - 1 ? 1 : 0);
            if (attempt < RetryPolicy.MAX_ATTEMPTS - 1) {
                var checkpoint = checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow();
                assertThat(checkpoint.status())
                        .isEqualTo(CheckpointService.Status.RETRYABLE);
                if (attempt == 0) {
                    assertThat(checkpoint.lastDiagnosticCode()).isEqualTo("WORKER_UNAVAILABLE");
                }
            }
            Thread.sleep(10L);
        }

        assertThat(calls).hasValue(RetryPolicy.MAX_ATTEMPTS);
        assertThat(checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow().status())
                .isEqualTo(CheckpointService.Status.DEAD_LETTER);
        assertThat(redisTemplate.opsForStream().size(RedisStreamNames.deadLetter())).isEqualTo(1);
        assertThat(redisTemplate.opsForStream().range(RedisStreamNames.deadLetter(),
                org.springframework.data.domain.Range.unbounded()).getFirst().getValue().toString())
                .doesNotContain("private source", event.learnerId().toString());
    }

    @Test
    void staleRunningLeaseIsRecoveredAndCompletesOnReplay() {
        var event = insertEvent();
        publish(event.eventId(), event.outboxId());
        checkpoints = new CheckpointService(jdbcClient, transactionManager, Duration.ofSeconds(1));
        checkpoints.transactions().executeWithoutResult(ignored -> checkpoints.claim(
                event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1,
                event.learnerId(), event.outboxId(), NOW.minusSeconds(2)));
        var calls = new AtomicInteger();

        assertThat(consumer("stale-lease", NOW, terminalProcessor(calls,
                new CandidateProcessingService.StageOutcome.Success())).drainOnce()).isOne();

        assertThat(calls).hasValue(1);
        assertThat(checkpoints.find(event.eventId(), "v1", StageName.CANDIDATE_EXTRACTION, 1).orElseThrow().status())
                .isEqualTo(CheckpointService.Status.SUCCEEDED);
    }

    @Test
    void schemaExactIdentifierFlowsThroughWorkerAndM1GovernanceOnce() {
        var event = insertEvent();
        insertOptIn(event.learnerId());
        publish(event.eventId(), event.outboxId());
        var properties = CandidateProcessingServiceTest.properties();
        var sourceText = "请以后回答短一点";
        var source = new CandidateProcessingService.AuthorizedSource(
                event.eventId(), "v1", "subject-" + event.learnerId(), 1, sourceText,
                WorkerProposalValidator.sha256(sourceText), PrivacyLevel.SENSITIVE,
                SourceKind.EXPLICIT_DECLARATION, NOW, Set.of(MemoryType.PREFERENCE),
                "zh-CN", "consent-revision-1", "trace-worker-a");
        var refetches = new AtomicInteger();
        CandidateProcessingService.AuthoritySourceReader reader = (outboxId, eventId) -> {
            assertThat(outboxId).isEqualTo(event.outboxId());
            assertThat(eventId).isEqualTo(event.eventId());
            refetches.incrementAndGet();
            return CandidateProcessingService.SourceReadOutcome.authorized(source);
        };
        var workerInTransaction = new AtomicBoolean(true);
        CandidateWorkerClient worker = (request, deadline) -> {
            workerInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return CandidateWorkerClient.WorkerResult.success(new CandidateWorkerClient.WorkerResponse(
                    request.requestId(), request.schemaVersion(), List.of(new CandidateWorkerClient.WorkerProposal(
                    "untrusted-worker-id", "PREFERENCE", "{\"answer_style\":\"concise\"}", 0.8d,
                    "SENSITIVE", List.of(new CandidateWorkerClient.SourceSpan(
                    request.sourceId(), 0, sourceText.getBytes(StandardCharsets.UTF_8).length,
                    request.sourceDigest())))), ""));
        };
        var protector = testProtector();
        var governance = new GovernCandidateUseCase(
                new PromotionRule(properties), new JdbcCandidateRepository(jdbcClient, protector),
                new JdbcMemoryHistoryRepository(jdbcClient, protector), new JdbcConsentRepository(jdbcClient),
                new TransactionTemplate(transactionManager), Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        try {
            var processor = new CandidateProcessingService(reader, worker, new WorkerProposalValidator(), governance,
                    properties, Clock.fixed(NOW, java.time.ZoneOffset.UTC), workerExecutor);

            assertThat(consumer("schema-exact", NOW, processor).drainOnce()).isOne();
        } finally {
            workerExecutor.shutdownNow();
        }

        assertThat(workerInTransaction).isFalse();
        assertThat(refetches).hasValue(2);
        assertThat(count("memory_version")).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM transactional_outbox WHERE event_type = 'MEMORY_VERSION_ACCEPTED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    private MemoryEventConsumer consumer(String group, Instant now, AsyncStageProcessor processor) {
        return new MemoryEventConsumer(
                jdbcClient,
                redisTemplate,
                checkpoints,
                processor,
                group,
                "consumer-" + UUID.randomUUID(),
                10,
                Duration.ZERO,
                Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    private static AsyncStageProcessor terminalProcessor(
            AtomicInteger calls,
            CandidateProcessingService.StageOutcome outcome) {
        return new AsyncStageProcessor() {
            @Override
            public PreparedAttempt prepareOutsideTransaction(EventContext context) {
                calls.incrementAndGet();
                return new TestAttempt();
            }

            @Override
            public CandidateProcessingService.StageOutcome finalizeInTransaction(
                    EventContext context,
                    PreparedAttempt attempt) {
                return outcome;
            }
        };
    }

    private static AsyncStageProcessor retryingProcessor(AtomicInteger calls, String diagnosticCode) {
        return terminalProcessor(calls, new CandidateProcessingService.StageOutcome.Retryable(diagnosticCode));
    }

    private EventFixture insertEvent() {
        var learnerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        insertLearner(learnerId);
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
                            :eventId, :schemaVersion, :learnerId, 'session-m3', 'PREFERENCE', 'EXPLICIT_DECLARATION',
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
                .param("payloadDigest", (schemaVersion.equals("v1") ? "b" : "c").repeat(64))
                .param("keyReference", protectedPayload.keyReference())
                .param("nonce", protectedPayload.nonce())
                .param("algorithm", EncryptedPayload.ALGORITHM)
                .param("cryptoVersion", EncryptedPayload.CRYPTO_VERSION)
                .update();
        jdbcClient.sql("""
                        INSERT INTO transactional_outbox (outbox_id, aggregate_id, event_type, payload, created_at)
                        VALUES (:outboxId, :eventId, 'INTERACTION_EVENT_ACCEPTED', CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("outboxId", outboxId)
                .param("eventId", existing.eventId())
                .param("payload", "{\"event_id\":\"" + existing.eventId()
                        + "\",\"event_schema_version\":\"" + schemaVersion + "\"}")
                .param("createdAt", Timestamp.from(NOW))
                .update();
        return new EventFixture(existing.eventId(), existing.learnerId(), outboxId);
    }

    private void insertLearner(UUID learnerId) {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:learnerId, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("learnerId", learnerId)
                .param("subjectHash", "subject-" + learnerId)
                .param("createdAt", Timestamp.from(NOW))
                .update();
    }

    private void insertOptIn(UUID learnerId) {
        jdbcClient.sql("""
                        INSERT INTO consent_policy_version (
                            consent_policy_version_id, learner_subject_id, revision, status,
                            allowed_categories, valid_from, valid_until, created_at)
                        VALUES (:policyId, :learnerId, 1, 'ACTIVE', CAST(:categories AS jsonb),
                                :validFrom, :validUntil, :createdAt)
                        """)
                .param("policyId", UUID.randomUUID())
                .param("learnerId", learnerId)
                .param("categories", "[\"PREFERENCE\"]")
                .param("validFrom", Timestamp.from(Instant.EPOCH))
                .param("validUntil", Timestamp.from(NOW.plus(Duration.ofDays(1))))
                .param("createdAt", Timestamp.from(NOW))
                .update();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void publish(UUID eventId, UUID outboxId) {
        var envelope = new StreamEnvelope(
                StreamEnvelope.SCHEMA_VERSION,
                outboxId,
                eventId,
                "c".repeat(64),
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        redisTemplate.opsForStream().add(
                StreamRecords.mapBacked(envelope.redisFields()).withStreamKey(RedisStreamNames.events()));
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
