package com.yilan.memory.application.event;

import com.yilan.memory.adapter.postgres.JdbcConsentRepository;
import com.yilan.memory.adapter.postgres.JdbcInteractionEventRepository;
import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.application.privacy.DataKeyProvider;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.event.InteractionEvent;
import com.yilan.memory.domain.identity.LearnerIdentity;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.UUID;

import static com.yilan.memory.application.event.SubmitMemoryEventsUseCase.EventResult.ACCEPTED;
import static com.yilan.memory.application.event.SubmitMemoryEventsUseCase.EventResult.DUPLICATE;
import static com.yilan.memory.application.event.SubmitMemoryEventsUseCase.EventResult.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class EventIngestIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final LearnerIdentity IDENTITY = new LearnerIdentity("subject-event-a", "session-event-1", 7);

    @Autowired
    private SubmitMemoryEventsUseCase useCase;

    @Autowired
    private JdbcInteractionEventRepository eventRepository;

    @Autowired
    private JdbcConsentRepository consentPolicyReader;

    @Autowired
    private SubmitMemoryEventsUseCase.TransactionalOutboxRepository transactionalOutboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void createCurrentOptIn() {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:id, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("subjectHash", IDENTITY.subjectHash())
                .param("createdAt", Timestamp.from(NOW))
                .update();
        insertCurrentOptIn();
    }

    private void insertCurrentOptIn() {
        jdbcClient.sql("""
                        INSERT INTO consent_policy_version (
                            consent_policy_version_id, learner_subject_id, revision, status,
                            allowed_categories, valid_from, valid_until, created_at)
                        SELECT :id, learner_subject_id, :revision, 'ACTIVE',
                               CAST(:categories AS jsonb), :validFrom, :validUntil, :createdAt
                        FROM learner_subject
                        WHERE subject_hash = :subjectHash
                        """)
                .param("id", UUID.randomUUID())
                .param("revision", IDENTITY.consentRevision())
                .param("categories", "[\"PREFERENCE\"]")
                .param("validFrom", Timestamp.from(Instant.EPOCH))
                .param("validUntil", Timestamp.from(Instant.parse("2100-01-01T00:00:00Z")))
                .param("createdAt", Timestamp.from(NOW))
                .param("subjectHash", IDENTITY.subjectHash())
                .update();
    }

    @AfterEach
    void clearAuthorityRows() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }

    @Test
    void eventAndOutboxCommitAtomicallyAndDuplicateIsSuccess() {
        var command = command(UUID.randomUUID());

        assertThat(useCase.submit(IDENTITY, List.of(command)).getFirst().result()).isEqualTo(ACCEPTED);
        assertThat(useCase.submit(IDENTITY, List.of(command)).getFirst().result()).isEqualTo(DUPLICATE);
        assertThat(count("interaction_event")).isEqualTo(1);
        assertThat(count("transactional_outbox")).isEqualTo(1);
    }

    @Test
    void sameEventIdWithDifferentSchemaVersionsCreatesIndependentEvents() {
        var eventId = UUID.randomUUID();
        var schemaV1 = command(eventId, "v1");
        var schemaV2 = command(eventId, "v2");

        assertThat(useCase.submit(IDENTITY, List.of(schemaV1)).getFirst().result()).isEqualTo(ACCEPTED);
        assertThat(useCase.submit(IDENTITY, List.of(schemaV2)).getFirst().result()).isEqualTo(ACCEPTED);
        assertThat(useCase.submit(IDENTITY, List.of(schemaV1)).getFirst().result()).isEqualTo(DUPLICATE);
        assertThat(count("interaction_event")).isEqualTo(2);
        assertThat(count("transactional_outbox")).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT schema_version FROM interaction_event ORDER BY schema_version")
                .query(String.class).list()).containsExactly("v1", "v2");
    }

    @Test
    void repositoryClassifiesTheSamePairByItsDurableSubjectOwner() {
        var eventId = UUID.randomUUID();
        var event = InteractionEvent.opaque(
                eventId,
                "v1",
                "PREFERENCE",
                SourceKind.GENERAL,
                NOW,
                NOW,
                PrivacyLevel.STANDARD,
                IDENTITY.consentRevision(),
                "opaque-event-body".getBytes(StandardCharsets.UTF_8),
                "trace-event-1");

        assertThat(eventRepository.insertIfAbsent(IDENTITY, event))
                .isEqualTo(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        assertThat(eventRepository.insertIfAbsent(IDENTITY, event))
                .isEqualTo(SubmitMemoryEventsUseCase.InsertOutcome.DUPLICATE);

        var otherIdentity = new LearnerIdentity("subject-event-b", "session-event-2", 7);
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:id, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("subjectHash", otherIdentity.subjectHash())
                .param("createdAt", Timestamp.from(NOW))
                .update();

        assertThat(eventRepository.insertIfAbsent(otherIdentity, event))
                .isEqualTo(SubmitMemoryEventsUseCase.InsertOutcome.CROSS_SUBJECT);
        assertThat(count("interaction_event")).isEqualTo(1);
    }

    @Test
    void concurrentSameIdempotencyKeyCreatesOneEventAndOneOutbox() throws Exception {
        var sharedCommand = command(UUID.randomUUID());
        var barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> submitAfterBarrier(barrier, sharedCommand));
            var second = executor.submit(() -> submitAfterBarrier(barrier, sharedCommand));
            var receipts = List.of(first.get(), second.get());

            assertThat(receipts)
                    .extracting(SubmitMemoryEventsUseCase.EventReceipt::result)
                    .containsExactlyInAnyOrder(ACCEPTED, DUPLICATE);
        }
        assertThat(count("interaction_event")).isEqualTo(1);
        assertThat(count("transactional_outbox")).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackTheNewEventAndOutboxTogether() {
        var failingUseCase = new SubmitMemoryEventsUseCase(
                eventRepository,
                event -> {
                    throw new DataAccessResourceFailureException("simulated local outbox failure");
                },
                consentPolicyReader);
        var transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> failingUseCase.submit(IDENTITY, List.of(command(UUID.randomUUID())))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("interaction_event")).isZero();
        assertThat(count("transactional_outbox")).isZero();
    }

    @Test
    void secondOutboxFailureRollsBackBothEventsAndTheFirstOutboxInOneBatchTransaction() {
        var outboxWrites = new AtomicInteger();
        var failingUseCase = new SubmitMemoryEventsUseCase(
                eventRepository,
                event -> {
                    transactionalOutboxRepository.insert(event);
                    if (outboxWrites.incrementAndGet() == 2) {
                        throw new DataAccessResourceFailureException("simulated second outbox failure");
                    }
                },
                consentPolicyReader);
        var transactionTemplate = new TransactionTemplate(transactionManager);
        var commands = List.of(command(UUID.randomUUID()), command(UUID.randomUUID()));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> failingUseCase.submit(IDENTITY, commands)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(outboxWrites).hasValue(2);
        assertThat(count("interaction_event")).isZero();
        assertThat(count("transactional_outbox")).isZero();
    }

    @Test
    void localDemoIngressStoresOnlyASealedFrameAndSealFailureRollsBackEventAndOutbox() {
        var sourceSentinel = "source sentinel";
        var eventId = UUID.randomUUID();
        var sourceEnvelopes = new InMemoryTestKeyProvider.InMemoryInteractionPayloadKeyEnvelopeStore();
        var protectingUseCase = localDemoUseCase(testProtector(sourceEnvelopes), sourceEnvelopes);
        var transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(ignored ->
                protectingUseCase.submit(IDENTITY, List.of(sourceCommand(eventId, sourceSentinel))));

        var stored = jdbcClient.sql("SELECT payload_ciphertext FROM interaction_event WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(byte[].class)
                .single();
        assertThat(stored).startsWith((byte) 'S', (byte) 'M', (byte) 'E', (byte) '1');
        assertThat(contains(stored, sourceSentinel.getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(jdbcClient.sql("SELECT payload_digest FROM interaction_event WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single()).isEqualTo(sha256(stored));
        assertThat(jdbcClient.sql("SELECT payload::text FROM transactional_outbox WHERE aggregate_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single()).doesNotContain(sourceSentinel);

        var unavailableSourceEnvelopes = new InMemoryTestKeyProvider.InMemoryInteractionPayloadKeyEnvelopeStore();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored -> localDemoUseCase(
                unavailableProtector(), unavailableSourceEnvelopes).submit(
                        IDENTITY, List.of(sourceCommand(UUID.randomUUID(), "other sentinel")))))
                .isInstanceOf(SecurityException.class);
        assertThat(count("interaction_event")).isEqualTo(1);
        assertThat(count("transactional_outbox")).isEqualTo(1);
    }

    @Test
    void deniedConsentAndCrossSubjectOrStaleConsentCommandsWriteNoRows() {
        jdbcClient.sql("DELETE FROM consent_policy_version").update();
        var denied = useCase.submit(IDENTITY, List.of(command(UUID.randomUUID()))).getFirst();

        assertThat(denied.result()).isEqualTo(REJECTED);
        assertThat(denied.reasonCode()).isEqualTo("MISSING_POLICY");
        assertThat(count("interaction_event")).isZero();
        assertThat(count("transactional_outbox")).isZero();

        insertCurrentOptIn();
        var crossSubject = useCase.submit(IDENTITY, List.of(command(
                UUID.randomUUID(), "subject-event-b", IDENTITY.consentRevision()))).getFirst();
        var staleConsent = useCase.submit(IDENTITY, List.of(command(
                UUID.randomUUID(), IDENTITY.subjectHash(), IDENTITY.consentRevision() - 1))).getFirst();

        assertThat(crossSubject.result()).isEqualTo(REJECTED);
        assertThat(crossSubject.reasonCode()).isEqualTo("CROSS_SUBJECT");
        assertThat(staleConsent.result()).isEqualTo(REJECTED);
        assertThat(staleConsent.reasonCode()).isEqualTo("STALE_EVENT_CONSENT");
        assertThat(count("interaction_event")).isZero();
        assertThat(count("transactional_outbox")).isZero();
    }

    @Test
    void proxiedUseCaseRejectsAnEntireBatchBeforeWritingWhenACommandHasCrossSubjectOrStaleConsent() {
        assertThat(AopUtils.isAopProxy(useCase)).isTrue();
        var firstLegal = command(UUID.randomUUID());
        var crossSubject = command(UUID.randomUUID(), "subject-event-b", IDENTITY.consentRevision());

        var crossSubjectReceipts = useCase.submit(IDENTITY, List.of(firstLegal, crossSubject));

        assertThat(crossSubjectReceipts)
                .extracting(SubmitMemoryEventsUseCase.EventReceipt::result)
                .containsOnly(REJECTED);
        assertThat(crossSubjectReceipts)
                .extracting(SubmitMemoryEventsUseCase.EventReceipt::reasonCode)
                .containsOnly("BATCH_REJECTED");
        assertThat(count("interaction_event")).isZero();
        assertThat(count("transactional_outbox")).isZero();

        var staleFirstLegal = command(UUID.randomUUID());
        var staleConsent = command(
                UUID.randomUUID(), IDENTITY.subjectHash(), IDENTITY.consentRevision() - 1);

        var staleConsentReceipts = useCase.submit(IDENTITY, List.of(staleFirstLegal, staleConsent));

        assertThat(staleConsentReceipts)
                .extracting(SubmitMemoryEventsUseCase.EventReceipt::result)
                .containsOnly(REJECTED);
        assertThat(staleConsentReceipts)
                .extracting(SubmitMemoryEventsUseCase.EventReceipt::reasonCode)
                .containsOnly("BATCH_REJECTED");
        assertThat(count("interaction_event")).isZero();
        assertThat(count("transactional_outbox")).isZero();
    }

    @Test
    void proxiedUseCaseRollsBackTheEntireBatchWhenDurableIdempotencyBelongsToAnotherSubject() {
        var otherIdentity = new LearnerIdentity("subject-event-b", "session-event-2", 7);
        createActiveSubject(otherIdentity);
        var occupiedEventId = UUID.randomUUID();
        var preexisting = InteractionEvent.opaque(
                occupiedEventId,
                "v1",
                "PREFERENCE",
                SourceKind.GENERAL,
                NOW,
                NOW,
                PrivacyLevel.STANDARD,
                otherIdentity.consentRevision(),
                "other-subject-event".getBytes(StandardCharsets.UTF_8),
                "trace-event-2");
        assertThat(eventRepository.insertIfAbsent(otherIdentity, preexisting))
                .isEqualTo(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        assertThat(count("interaction_event")).isEqualTo(1);
        assertThat(count("transactional_outbox")).isZero();

        var newLegal = command(UUID.randomUUID());
        var occupied = command(occupiedEventId);

        assertThatThrownBy(() -> useCase.submit(IDENTITY, List.of(newLegal, occupied)))
                .isInstanceOfSatisfying(SubmitMemoryEventsUseCase.BatchRejectedException.class, rejection -> {
                    assertThat(rejection.reasonCode()).isEqualTo("CROSS_SUBJECT");
                    assertThat(rejection.eventId()).isEqualTo(occupiedEventId);
                });
        assertThat(count("interaction_event")).isEqualTo(1);
        assertThat(count("transactional_outbox")).isZero();
    }

    @Test
    void sourceKindIsJavaValidatedAndPersistedWithTheAuthorityEvent() {
        var command = command(
                UUID.randomUUID(), IDENTITY.subjectHash(), IDENTITY.consentRevision(),
                SourceKind.EXPLICIT_DECLARATION);

        assertThat(useCase.submit(IDENTITY, List.of(command)).getFirst().result()).isEqualTo(ACCEPTED);
        assertThat(jdbcClient.sql("SELECT source_kind FROM interaction_event")
                .query(String.class).single()).isEqualTo("EXPLICIT_DECLARATION");

        assertThatThrownBy(() -> command(
                UUID.randomUUID(), IDENTITY.subjectHash(), IDENTITY.consentRevision(),
                SourceKind.SCORED_ASSESSMENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("interaction_event")).isEqualTo(1);
    }

    private SubmitMemoryEventsUseCase.InteractionEventCommand command(UUID eventId) {
        return command(eventId, IDENTITY.subjectHash(), IDENTITY.consentRevision());
    }

    private void createActiveSubject(LearnerIdentity identity) {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:id, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("subjectHash", identity.subjectHash())
                .param("createdAt", Timestamp.from(NOW))
                .update();
    }

    private SubmitMemoryEventsUseCase.InteractionEventCommand command(UUID eventId, String schemaVersion) {
        return new SubmitMemoryEventsUseCase.InteractionEventCommand(
                eventId,
                schemaVersion,
                "PREFERENCE",
                MemoryCategory.PREFERENCE,
                SourceKind.GENERAL,
                IDENTITY.subjectHash(),
                IDENTITY.consentRevision(),
                NOW,
                PrivacyLevel.STANDARD,
                "opaque-event-body".getBytes(StandardCharsets.UTF_8),
                "trace-event-1");
    }

    private SubmitMemoryEventsUseCase.InteractionEventCommand sourceCommand(UUID eventId, String payload) {
        return new SubmitMemoryEventsUseCase.InteractionEventCommand(
                eventId,
                "v1",
                "PREFERENCE",
                MemoryCategory.PREFERENCE,
                SourceKind.GENERAL,
                IDENTITY.subjectHash(),
                IDENTITY.consentRevision(),
                NOW,
                PrivacyLevel.STANDARD,
                payload.getBytes(StandardCharsets.UTF_8),
                "trace-event-1");
    }

    private SubmitMemoryEventsUseCase localDemoUseCase(
            PayloadProtector protector,
            InMemoryTestKeyProvider.InMemoryInteractionPayloadKeyEnvelopeStore sourceEnvelopes) {
        return new SubmitMemoryEventsUseCase(
                eventRepository, transactionalOutboxRepository, consentPolicyReader, protector, sourceEnvelopes);
    }

    private static PayloadProtector testProtector(
            InMemoryTestKeyProvider.InMemoryInteractionPayloadKeyEnvelopeStore sourceEnvelopes) {
        var masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        return new PayloadProtector(new InMemoryTestKeyProvider(
                masterKey, new InMemoryTestKeyProvider.InMemoryEnvelopeStore(), sourceEnvelopes));
    }

    private static PayloadProtector unavailableProtector() {
        return new PayloadProtector(new DataKeyProvider() {
            @Override
            public KeyMaterial activeKeyFor(String subjectHash, KeyPurpose purpose) {
                throw new SecurityException("key unavailable");
            }

            @Override
            public KeyMaterial keyFor(String subjectHash, KeyPurpose purpose, String keyReference) {
                throw new SecurityException("key unavailable");
            }
        });
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

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

    private SubmitMemoryEventsUseCase.EventReceipt submitAfterBarrier(
            CyclicBarrier barrier,
            SubmitMemoryEventsUseCase.InteractionEventCommand command) throws Exception {
        barrier.await();
        return useCase.submit(IDENTITY, List.of(command)).getFirst();
    }

    private SubmitMemoryEventsUseCase.InteractionEventCommand command(
            UUID eventId,
            String declaredSubjectHash,
            long declaredConsentRevision) {
        return command(eventId, declaredSubjectHash, declaredConsentRevision, SourceKind.GENERAL);
    }

    private SubmitMemoryEventsUseCase.InteractionEventCommand command(
            UUID eventId,
            String declaredSubjectHash,
            long declaredConsentRevision,
            SourceKind sourceKind) {
        return new SubmitMemoryEventsUseCase.InteractionEventCommand(
                eventId,
                "v1",
                "PREFERENCE",
                MemoryCategory.PREFERENCE,
                sourceKind,
                declaredSubjectHash,
                declaredConsentRevision,
                NOW,
                PrivacyLevel.STANDARD,
                "opaque-event-body".getBytes(StandardCharsets.UTF_8),
                "trace-event-1");
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }
}
