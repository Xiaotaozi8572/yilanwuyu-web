package com.yilan.memory.adapter.postgres;

import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.governance.GovernCandidateUseCase;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.GovernanceDecision.Decision;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.governance.PromotionRule;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class MemoryHistoryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final String SUBJECT = "subject-governance-a";
    private static final byte[] TEST_MASTER_KEY = testMasterKey();

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private GovernCandidateUseCase useCase;

    @BeforeEach
    void setUp() {
        clearAuthorityRows();
        insertLearner(SUBJECT);
        insertOptIn(SUBJECT);
        useCase = new GovernCandidateUseCase(
                new PromotionRule(properties()),
                new JdbcCandidateRepository(jdbcClient, testProtector()),
                new JdbcMemoryHistoryRepository(jdbcClient, testProtector()),
                new JdbcConsentRepository(jdbcClient),
                new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() {
        clearAuthorityRows();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void acceptedDecisionWritesAppendOnlyHistoryAndSupersedesViaProjection() {
        var firstSource = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(2)));
        var first = useCase.govern(candidate(
                MemoryType.PREFERENCE,
                "explanation-style",
                List.of(source(firstSource))));

        assertThat(first.decision()).isEqualTo(Decision.ACCEPTED);
        assertThat(count("memory_candidate")).isEqualTo(1);
        assertThat(count("governance_decision")).isEqualTo(1);
        assertThat(count("memory_assertion")).isEqualTo(1);
        assertThat(count("memory_version")).isEqualTo(1);
        assertThat(count("memory_transition")).isEqualTo(1);
        assertThat(count("memory_source_link")).isEqualTo(1);
        assertThat(count("memory_head_projection")).isEqualTo(1);
        assertThat(count("memory_audit_event")).isEqualTo(1);
        assertThat(memoryEpoch()).isEqualTo(1);
        assertThat(transitionTypes()).containsExactly("ACTIVATE");

        var secondSource = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)));
        var second = useCase.govern(candidate(
                MemoryType.PREFERENCE,
                "explanation-style",
                List.of(source(secondSource))));

        assertThat(second.decision()).isEqualTo(Decision.ACCEPTED);
        assertThat(count("memory_assertion")).isEqualTo(1);
        assertThat(count("memory_version")).isEqualTo(2);
        assertThat(count("memory_transition")).isEqualTo(2);
        assertThat(count("memory_source_link")).isEqualTo(2);
        assertThat(memoryEpoch()).isEqualTo(2);
        assertThat(transitionTypes()).containsExactlyInAnyOrder("ACTIVATE", "SUPERSEDE");
        assertThat(jdbcClient.sql("SELECT count(*) FROM memory_version WHERE status = 'ACTIVE'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void onlyAcceptedGovernanceQueuesAnIdentifierOnlyDownstreamOutboxEvent() {
        var acceptedSource = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)));
        var acceptedCandidate = candidate(
                MemoryType.PREFERENCE, "downstream-outbox-preference", List.of(source(acceptedSource)));

        assertThat(useCase.govern(acceptedCandidate).decision()).isEqualTo(Decision.ACCEPTED);

        var outbox = jdbcClient.sql("""
                        SELECT aggregate_id, event_type, payload ->> 'event_schema_version' AS event_schema_version,
                               payload ->> 'memory_candidate_id' AS memory_candidate_id, payload::text AS payload
                        FROM transactional_outbox
                        """)
                .query((resultSet, ignored) -> new OutboxRow(
                        resultSet.getObject("aggregate_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getString("event_schema_version"),
                        resultSet.getString("memory_candidate_id"),
                        resultSet.getString("payload")))
                .single();
        assertAll(
                () -> assertThat(outbox.aggregateId()).isEqualTo(acceptedSource),
                () -> assertThat(outbox.eventType()).isEqualTo("MEMORY_VERSION_ACCEPTED"),
                () -> assertThat(outbox.eventSchemaVersion()).isEqualTo("v1"),
                () -> assertThat(outbox.memoryCandidateId()).isEqualTo(acceptedCandidate.candidateId().toString()),
                () -> assertThat(outbox.payload()).doesNotContain("concise", "opaque-candidate", SUBJECT));

        var pendingSource = insertEvent(SUBJECT, SourceKind.GENERAL, NOW.minus(Duration.ofMinutes(30)));
        assertThat(useCase.govern(candidate(
                MemoryType.MASTERY, "downstream-outbox-pending", List.of(source(pendingSource)))).decision())
                .isEqualTo(Decision.PENDING);
        assertThat(useCase.govern(candidate(
                MemoryType.AVIATION_FACT, "downstream-outbox-rejected", List.of(source(acceptedSource)))).decision())
                .isEqualTo(Decision.REJECTED);
        assertThat(useCase.rejectWorkerBoundary(candidate(
                MemoryType.OPTIMIZATION, "downstream-outbox-boundary", List.of(source(acceptedSource)))).decision())
                .isEqualTo(Decision.REJECTED);

        assertThat(count("transactional_outbox")).isEqualTo(1);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void ordinaryGeneralSourcesCannotBeFalselyPromotedAsEpisodeOrScoredEvidence() {
        var generalOne = insertEvent(SUBJECT, SourceKind.GENERAL, NOW.minus(Duration.ofDays(2)));
        var generalTwo = insertEvent(SUBJECT, SourceKind.GENERAL, NOW.minus(Duration.ofDays(1)));

        var mastery = useCase.govern(candidate(
                MemoryType.MASTERY,
                "general-cannot-be-scored",
                List.of(source(generalOne))));
        var reflection = useCase.govern(candidate(
                MemoryType.REFLECTION,
                "general-cannot-be-episode",
                List.of(source(generalOne), source(generalTwo))));

        assertThat(mastery.decision()).isEqualTo(Decision.PENDING);
        assertThat(mastery.reasonCodes()).contains("MASTERY_REQUIRES_SCORED_SOURCE");
        assertThat(reflection.decision()).isEqualTo(Decision.PENDING);
        assertThat(reflection.reasonCodes()).contains("REFLECTION_REQUIRES_TWO_EPISODES");
        assertThat(count("memory_candidate")).isEqualTo(2);
        assertThat(count("governance_decision")).isEqualTo(2);
        assertThat(count("memory_audit_event")).isEqualTo(2);
        assertThat(count("memory_version")).isZero();
        assertThat(count("memory_source_link")).isZero();
        assertThat(memoryEpoch()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void authoritativeHighPrivacySourceOverridesStandardCandidateAndPreventsAutomaticHistory() {
        var sourceEvent = insertEvent(
                SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)), PrivacyLevel.HIGH);

        var decision = useCase.govern(candidate(
                MemoryType.PREFERENCE,
                "high-privacy-source",
                PrivacyLevel.STANDARD,
                List.of(source(sourceEvent))));

        assertAll(
                () -> assertThat(interactionEventPrivacy(sourceEvent)).isEqualTo("HIGH"),
                () -> assertThat(decision.decision()).isEqualTo(Decision.PENDING),
                () -> assertThat(decision.reasonCodes()).containsExactly("HIGH_PRIVACY_REQUIRES_HUMAN_REVIEW"),
                () -> assertThat(candidatePrivacy()).isEqualTo("HIGH"),
                () -> assertThat(count("memory_version")).isZero(),
                () -> assertThat(count("memory_head_projection")).isZero(),
                () -> assertThat(count("memory_transition")).isZero(),
                () -> assertThat(memoryEpoch()).isZero());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void authoritativeSensitiveSourceRaisesAcceptedCandidateAndVersionPrivacy() {
        var sourceEvent = insertEvent(
                SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)), PrivacyLevel.SENSITIVE);

        var decision = useCase.govern(candidate(
                MemoryType.PREFERENCE,
                "sensitive-privacy-source",
                PrivacyLevel.STANDARD,
                List.of(source(sourceEvent))));

        assertAll(
                () -> assertThat(interactionEventPrivacy(sourceEvent)).isEqualTo("SENSITIVE"),
                () -> assertThat(decision.decision()).isEqualTo(Decision.ACCEPTED),
                () -> assertThat(candidatePrivacy()).isEqualTo("SENSITIVE"),
                () -> assertThat(memoryVersionPrivacy()).isEqualTo("SENSITIVE"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void unknownAuthorityPrivacyValueFailsClosedBeforeCandidateOrHistoryWrite() {
        var sourceEvent = insertEvent(
                SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)), "UNKNOWN");

        assertThatThrownBy(() -> useCase.govern(candidate(
                MemoryType.PREFERENCE,
                "unknown-authority-privacy",
                List.of(source(sourceEvent)))))
                .isInstanceOf(JdbcCandidateRepository.SourceValidationException.class);
        assertThat(count("memory_candidate")).isZero();
        assertThat(count("governance_decision")).isZero();
        assertThat(count("memory_audit_event")).isZero();
        assertThat(count("memory_version")).isZero();
        assertThat(memoryEpoch()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void candidateRetryReturnsTheImmutableDecisionAndWritesExactlyOneHistory() {
        var candidateId = UUID.randomUUID();
        var source = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)));
        var candidate = candidate(candidateId, SUBJECT, MemoryType.PREFERENCE, "retry-safe", NOW,
                List.of(source(source)));

        var first = useCase.govern(candidate);
        var replay = useCase.govern(candidate);

        assertThat(replay).isEqualTo(first);
        assertThat(count("memory_candidate")).isEqualTo(1);
        assertThat(count("governance_decision")).isEqualTo(1);
        assertThat(count("memory_audit_event")).isEqualTo(1);
        assertThat(count("memory_version")).isEqualTo(1);
        assertThat(count("memory_transition")).isEqualTo(1);
        assertThat(count("memory_source_link")).isEqualTo(1);
        assertThat(memoryEpoch()).isEqualTo(1);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void candidateGovernanceAuditIsFiniteAndDoesNotContainCandidateOrSubjectContent() {
        var source = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)));
        var candidate = candidate(
                MemoryType.PREFERENCE, "m4-audit-metadata", List.of(source(source)));

        assertThat(useCase.govern(candidate).decision()).isEqualTo(Decision.ACCEPTED);

        var metadata = jdbcClient.sql("SELECT redacted_metadata::text FROM memory_audit_event")
                .query(String.class)
                .single();
        assertAll(
                () -> assertThat(metadata).contains(
                        candidate.candidateId().toString(),
                        "PREFERENCE",
                        "rules-2026-07-19",
                        "ACCEPTED",
                        "RULES_SATISFIED"),
                () -> assertThat(metadata).doesNotContain("opaque-candidate", SUBJECT));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrentCandidateRetryWritesExactlyOneDecisionAndEpoch() throws Exception {
        var source = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)));
        var candidate = candidate(UUID.randomUUID(), SUBJECT, MemoryType.PREFERENCE, "concurrent-retry", NOW,
                List.of(source(source)));
        var barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> governAfterBarrier(barrier, candidate));
            var second = executor.submit(() -> governAfterBarrier(barrier, candidate));
            assertThat(List.of(first.get(), second.get())).containsOnly(Decision.ACCEPTED);
        }

        assertThat(count("memory_candidate")).isEqualTo(1);
        assertThat(count("governance_decision")).isEqualTo(1);
        assertThat(count("memory_audit_event")).isEqualTo(1);
        assertThat(count("memory_version")).isEqualTo(1);
        assertThat(count("memory_transition")).isEqualTo(1);
        assertThat(count("memory_source_link")).isEqualTo(1);
        assertThat(memoryEpoch()).isEqualTo(1);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void candidateIdOwnedByAnotherLearnerFailsClosedWithoutAdditionalHistory() {
        var candidateId = UUID.randomUUID();
        var firstSource = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)));
        useCase.govern(candidate(candidateId, SUBJECT, MemoryType.PREFERENCE, "owned-candidate", NOW,
                List.of(source(firstSource))));

        var otherSubject = "subject-governance-b";
        insertLearner(otherSubject);
        insertOptIn(otherSubject);
        var otherSource = insertEvent(otherSubject, SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> useCase.govern(candidate(
                candidateId, otherSubject, MemoryType.PREFERENCE, "owned-candidate", NOW,
                List.of(source(otherSource)))))
                .isInstanceOf(JdbcCandidateRepository.CandidateOwnershipException.class);
        assertThat(count("memory_candidate")).isEqualTo(1);
        assertThat(count("governance_decision")).isEqualTo(1);
        assertThat(count("memory_audit_event")).isEqualTo(1);
        assertThat(count("memory_version")).isEqualTo(1);
        assertThat(memoryEpoch()).isEqualTo(1);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void authorityClockSeparatesRecordedTimeFromLatestValidatedSourceTime() {
        var earlier = NOW.minus(Duration.ofDays(20));
        var latest = NOW.minus(Duration.ofDays(3));
        var futureCandidateObservation = NOW.plus(Duration.ofDays(3650));
        var firstSource = insertEvent(SUBJECT, SourceKind.EPISODE, earlier);
        var secondSource = insertEvent(SUBJECT, SourceKind.EPISODE, latest);

        var decision = useCase.govern(candidate(
                UUID.randomUUID(), SUBJECT, MemoryType.REFLECTION, "bitemporal-reflection",
                futureCandidateObservation, List.of(source(firstSource), source(secondSource))));

        assertThat(decision.decision()).isEqualTo(Decision.ACCEPTED);
        assertThat(decision.decidedAt()).isEqualTo(NOW);
        assertThat(timestamp("SELECT created_at FROM memory_candidate")).isEqualTo(NOW);
        assertThat(timestamp("SELECT decided_at FROM governance_decision")).isEqualTo(NOW);
        assertThat(timestamp("SELECT created_at FROM memory_audit_event")).isEqualTo(NOW);
        assertThat(timestamp("SELECT created_at FROM memory_assertion")).isEqualTo(NOW);
        assertThat(timestamp("SELECT recorded_at FROM memory_version")).isEqualTo(NOW);
        assertThat(timestamp("SELECT created_at FROM memory_version")).isEqualTo(NOW);
        assertThat(timestamp("SELECT transitioned_at FROM memory_transition")).isEqualTo(NOW);
        assertThat(timestamp("SELECT min(created_at) FROM memory_source_link")).isEqualTo(NOW);
        assertThat(timestamp("SELECT updated_at FROM memory_head_projection")).isEqualTo(NOW);
        assertThat(timestamp("SELECT updated_at FROM learner_epoch")).isEqualTo(NOW);
        assertThat(timestamp("SELECT valid_from FROM memory_version")).isEqualTo(latest);
        assertThat(timestamp("SELECT valid_until FROM memory_version")).isEqualTo(latest.plus(Duration.ofDays(90)));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void rejectedConsentDoesNotCreateReadableHistory() {
        jdbcClient.sql("DELETE FROM consent_policy_version").update();
        var source = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW);

        var decision = useCase.govern(candidate(
                MemoryType.PREFERENCE,
                "preference-without-current-consent",
                List.of(source(source))));

        assertThat(decision.decision()).isEqualTo(Decision.REJECTED);
        assertThat(decision.reasonCodes()).contains("CONSENT_MISSING_POLICY");
        assertThat(count("memory_candidate")).isEqualTo(1);
        assertThat(count("governance_decision")).isEqualTo(1);
        assertThat(count("memory_version")).isZero();
        assertThat(memoryEpoch()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void aviationFactIsRejectedAuditedAndNeverCreatesHistory() {
        var source = insertEvent(SUBJECT, SourceKind.GENERAL, NOW);

        var decision = useCase.govern(candidate(
                MemoryType.AVIATION_FACT,
                "aircraft-fact-must-not-be-memory",
                List.of(source(source))));

        assertThat(decision.decision()).isEqualTo(Decision.REJECTED);
        assertThat(decision.reasonCodes()).contains("AVIATION_FACT_FORBIDDEN");
        assertThat(count("memory_candidate")).isEqualTo(1);
        assertThat(count("governance_decision")).isEqualTo(1);
        assertThat(count("memory_audit_event")).isEqualTo(1);
        assertThat(count("memory_version")).isZero();
        assertThat(memoryEpoch()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void nonexistentAndCrossLearnerSourcesRollbackCandidateDecisionAndHistory() {
        assertThatThrownBy(() -> useCase.govern(candidate(
                MemoryType.PREFERENCE,
                "nonexistent-source",
                List.of(source(UUID.randomUUID())))))
                .isInstanceOf(JdbcCandidateRepository.SourceValidationException.class);

        insertLearner("subject-governance-b");
        var otherLearnerEvent = insertEvent("subject-governance-b", SourceKind.EXPLICIT_DECLARATION, NOW);
        var ownEvent = insertEvent(SUBJECT, SourceKind.EXPLICIT_DECLARATION, NOW);
        assertThatThrownBy(() -> useCase.govern(candidate(
                MemoryType.PREFERENCE,
                "cross-learner-source",
                List.of(source(ownEvent), source(otherLearnerEvent)))))
                .isInstanceOf(JdbcCandidateRepository.SourceValidationException.class);

        assertThat(count("memory_candidate")).isZero();
        assertThat(count("governance_decision")).isZero();
        assertThat(count("memory_version")).isZero();
        assertThat(memoryEpoch()).isZero();
    }

    private Decision governAfterBarrier(CyclicBarrier barrier, MemoryCandidate candidate) throws Exception {
        barrier.await();
        return useCase.govern(candidate).decision();
    }

    private MemoryCandidate candidate(
            MemoryType type,
            String assertionKey,
            List<MemoryCandidate.SourceReference> sources) {
        return candidate(type, assertionKey, PrivacyLevel.STANDARD, sources);
    }

    private MemoryCandidate candidate(
            MemoryType type,
            String assertionKey,
            PrivacyLevel privacyLevel,
            List<MemoryCandidate.SourceReference> sources) {
        return candidate(UUID.randomUUID(), SUBJECT, type, assertionKey, NOW, privacyLevel, sources);
    }

    private MemoryCandidate candidate(
            UUID candidateId,
            String subjectHash,
            MemoryType type,
            String assertionKey,
            Instant observedAt,
            List<MemoryCandidate.SourceReference> sources) {
        return candidate(candidateId, subjectHash, type, assertionKey, observedAt, PrivacyLevel.STANDARD, sources);
    }

    private MemoryCandidate candidate(
            UUID candidateId,
            String subjectHash,
            MemoryType type,
            String assertionKey,
            Instant observedAt,
            PrivacyLevel privacyLevel,
            List<MemoryCandidate.SourceReference> sources) {
        return new MemoryCandidate(
                candidateId,
                subjectHash,
                3,
                type,
                assertionKey,
                valueJson(type),
                new BigDecimal("0.90"),
                new BigDecimal("0.80"),
                privacyLevel,
                observedAt,
                sources,
                "opaque-candidate".getBytes(StandardCharsets.UTF_8));
    }

    private String valueJson(MemoryType type) {
        return switch (type) {
            case PREFERENCE -> "{\"answer_style\":\"concise\"}";
            case MASTERY -> "{\"mastery_level\":\"developing\"}";
            case MISCONCEPTION -> "{\"misconception_code\":\"lift_drag_confusion\"}";
            case REFLECTION -> "{\"reflection_code\":\"reviewed_basics\"}";
            case AVIATION_FACT, OPTIMIZATION -> "{}";
        };
    }

    private MemoryCandidate.SourceReference source(UUID eventId) {
        return new MemoryCandidate.SourceReference(eventId, "v1");
    }

    private MemoryProperties properties() {
        var rules = new EnumMap<MemoryType, MemoryProperties.TypeRule>(MemoryType.class);
        for (var type : MemoryType.values()) {
            rules.put(type, new MemoryProperties.TypeRule(1, new BigDecimal("0.70"), Duration.ofDays(30)));
        }
        rules.put(MemoryType.MISCONCEPTION, new MemoryProperties.TypeRule(2, new BigDecimal("0.70"), Duration.ofDays(14)));
        rules.put(MemoryType.REFLECTION, new MemoryProperties.TypeRule(2, new BigDecimal("0.70"), Duration.ofDays(7)));
        return new MemoryProperties(
                "rules-2026-07-19", rules,
                new MemoryProperties.RetrievalWeights(
                        new BigDecimal("0.30"), new BigDecimal("0.20"),
                        new BigDecimal("0.50"), new BigDecimal("0.10")),
                60, new MemoryProperties.Budgets(8, 900));
    }

    private void insertLearner(String subjectHash) {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:id, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("subjectHash", subjectHash)
                .param("createdAt", Timestamp.from(NOW))
                .update();
    }

    private void insertOptIn(String subjectHash) {
        jdbcClient.sql("""
                        INSERT INTO consent_policy_version (
                            consent_policy_version_id, learner_subject_id, revision, status,
                            allowed_categories, valid_from, valid_until, created_at)
                        SELECT :id, learner_subject_id, 3, 'ACTIVE',
                               CAST(:categories AS jsonb), :validFrom, :validUntil, :createdAt
                        FROM learner_subject WHERE subject_hash = :subjectHash
                        """)
                .param("id", UUID.randomUUID())
                .param("categories", "[\"PREFERENCE\",\"MASTERY\",\"MISCONCEPTION\",\"REFLECTION\"]")
                .param("validFrom", Timestamp.from(Instant.EPOCH))
                .param("validUntil", Timestamp.from(Instant.parse("2100-01-01T00:00:00Z")))
                .param("createdAt", Timestamp.from(NOW))
                .param("subjectHash", subjectHash)
                .update();
    }

    private UUID insertEvent(String subjectHash, SourceKind sourceKind, Instant occurredAt) {
        return insertEvent(subjectHash, sourceKind, occurredAt, PrivacyLevel.STANDARD);
    }

    private UUID insertEvent(
            String subjectHash,
            SourceKind sourceKind,
            Instant occurredAt,
            PrivacyLevel privacyLevel) {
        return insertEvent(subjectHash, sourceKind, occurredAt, privacyLevel.name());
    }

    private UUID insertEvent(
            String subjectHash,
            SourceKind sourceKind,
            Instant occurredAt,
            String privacyLevel) {
        var eventId = UUID.randomUUID();
        var protectedPayload = "SENSITIVE".equals(privacyLevel) || "HIGH".equals(privacyLevel)
                ? EncryptedPayload.parse(testProtector().seal(new PayloadProtector.SourceMaterialBinding(
                        subjectHash, eventId, "v1", "interaction_event", KeyPurpose.INTERACTION_SOURCE),
                        "opaque-event".getBytes(StandardCharsets.UTF_8)))
                : null;
        var eventType = switch (sourceKind) {
            case EXPLICIT_DECLARATION -> "PREFERENCE";
            case EPISODE -> "REFLECTION";
            case SCORED_ASSESSMENT -> "MASTERY";
            case GENERAL -> "PREFERENCE";
        };
        jdbcClient.sql("""
                        INSERT INTO interaction_event (
                            event_id, schema_version, learner_subject_id, session_id, event_type, source_kind,
                            occurred_at, received_at, privacy_level, consent_revision,
                            payload_ciphertext, payload_digest, trace_id, payload_key_reference, payload_nonce,
                            payload_algorithm, payload_crypto_version)
                        SELECT :eventId, 'v1', learner_subject_id, 'session-governance', :eventType, :sourceKind,
                               :occurredAt, :receivedAt, :privacyLevel, 3, :payloadCiphertext,
                               :payloadDigest, 'trace-governance', :keyReference, :nonce, :algorithm, :cryptoVersion
                        FROM learner_subject WHERE subject_hash = :subjectHash
                        """)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("sourceKind", sourceKind.name())
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("receivedAt", Timestamp.from(NOW))
                .param("privacyLevel", privacyLevel)
                .param("payloadCiphertext", protectedPayload == null
                        ? "opaque-event".getBytes(StandardCharsets.UTF_8) : protectedPayload.serialize())
                .param("payloadDigest", "0".repeat(64))
                .param("keyReference", protectedPayload == null ? null : protectedPayload.keyReference())
                .param("nonce", protectedPayload == null ? null : protectedPayload.nonce())
                .param("algorithm", protectedPayload == null ? null : EncryptedPayload.ALGORITHM)
                .param("cryptoVersion", protectedPayload == null ? null : EncryptedPayload.CRYPTO_VERSION)
                .param("subjectHash", subjectHash)
                .update();
        return eventId;
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private Instant timestamp(String sql) {
        return jdbcClient.sql(sql).query(Timestamp.class).single().toInstant();
    }

    private long memoryEpoch() {
        return jdbcClient.sql("SELECT COALESCE(max(memory_epoch), 0) FROM learner_epoch")
                .query(Long.class).single();
    }

    private String interactionEventPrivacy(UUID eventId) {
        return jdbcClient.sql("SELECT privacy_level FROM interaction_event WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private String candidatePrivacy() {
        return jdbcClient.sql("SELECT privacy_level FROM memory_candidate")
                .query(String.class)
                .single();
    }

    private String memoryVersionPrivacy() {
        return jdbcClient.sql("SELECT privacy_level FROM memory_version")
                .query(String.class)
                .single();
    }

    private List<String> transitionTypes() {
        return jdbcClient.sql("SELECT transition_type FROM memory_transition ORDER BY transitioned_at")
                .query(String.class).list();
    }

    private record OutboxRow(
            UUID aggregateId,
            String eventType,
            String eventSchemaVersion,
            String memoryCandidateId,
            String payload) {
    }

    private void clearAuthorityRows() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }

    private static PayloadProtector testProtector() {
        return new PayloadProtector(new InMemoryTestKeyProvider(TEST_MASTER_KEY));
    }

    private static byte[] testMasterKey() {
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }
}
