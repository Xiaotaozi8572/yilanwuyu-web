package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class VectorMemoryQueryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        clearAuthorityRows();
    }

    @AfterEach
    void tearDown() {
        clearAuthorityRows();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void exactCosineSearchIsLearnerClosedAndRanksExplicitL2Normalized1024DimensionalFixtures() {
        var first = insertReadableMemory("learner-vector-a", "first", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");
        var second = insertReadableMemory("learner-vector-a", "second", vector(0.8f, 0.6f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");
        insertReadableMemory("learner-vector-b", "other", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");

        var results = new VectorMemoryQuery(jdbcClient).retrieve(query("learner-vector-a", vector(1.0f, 0.0f)));

        assertThat(results).extracting(RetrievalChannel.RankedMemory::memoryId)
                .containsExactly(first.memoryId(), second.memoryId());
        assertThat(results).extracting(RetrievalChannel.RankedMemory::score)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(results).allSatisfy(item -> assertThat(item.sourceClosed()).isTrue());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void hardFiltersExcludeExpiredHighPrivacyInactiveAndSourceIncompleteRows() {
        var readable = insertReadableMemory("learner-vector-a", "readable", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");
        insertReadableMemory("learner-vector-a", "expired", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(20), NOW.minusSeconds(1), true, "ACTIVE");
        insertReadableMemory("learner-vector-a", "high", vector(1.0f, 0.0f), PrivacyLevel.HIGH,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");
        insertReadableMemory("learner-vector-a", "stale", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "STALE");
        insertReadableMemory("learner-vector-a", "unclosed", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), false, "ACTIVE");

        var results = new VectorMemoryQuery(jdbcClient).retrieve(query("learner-vector-a", vector(1.0f, 0.0f)));

        assertThat(results).extracting(RetrievalChannel.RankedMemory::memoryId)
                .containsExactly(readable.memoryId());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void structuredKeywordAndRecentEpisodeChannelsUseTheSameAuthorityHardFilters() {
        var memory = insertReadableMemory("learner-vector-a", "explanation-style", vector(1.0f, 0.0f),
                PrivacyLevel.STANDARD, NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE",
                SourceKind.EPISODE);
        var query = authorizedQuery("learner-vector-a", "explanation-style", null);

        assertThat(new StructuredMemoryQuery(jdbcClient).retrieve(query))
                .extracting(RetrievalChannel.RankedMemory::memoryId).containsExactly(memory.memoryId());
        assertThat(new KeywordMemoryQuery(jdbcClient).retrieve(query))
                .extracting(RetrievalChannel.RankedMemory::memoryId).containsExactly(memory.memoryId());
        assertThat(new RecentEpisodeQuery(jdbcClient).retrieve(query))
                .extracting(RetrievalChannel.RankedMemory::memoryId).containsExactly(memory.memoryId());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void mismatchedStoredProfileIsAControlledSemanticOmissionSignal() {
        insertReadableMemory("learner-vector-a", "profile-mismatch", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");
        var mismatch = new ResolveMemoryContextUseCase.QueryEmbedding(
                vectorValues(1.0f, 0.0f), "bge-m3", "v2", 1024, "L2");

        assertThatThrownBy(() -> new VectorMemoryQuery(jdbcClient)
                .retrieve(authorizedQuery("learner-vector-a", "profile-mismatch", mismatch)))
                .isInstanceOf(RetrievalChannel.SemanticUnavailableException.class)
                .hasMessage("EMBEDDING_PROFILE_MISMATCH");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void legacyUnspecifiedEmbeddingIsAControlledSemanticOmissionAndReturnsNoContent() {
        insertReadableMemory("learner-vector-legacy", "legacy-unspecified", vector(1.0f, 0.0f),
                PrivacyLevel.STANDARD, NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE", "UNSPECIFIED");

        assertThatThrownBy(() -> new VectorMemoryQuery(jdbcClient)
                .retrieve(query("learner-vector-legacy", vector(1.0f, 0.0f))))
                .isInstanceOf(RetrievalChannel.SemanticUnavailableException.class)
                .hasMessage("EMBEDDING_PROFILE_MISMATCH");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void databaseDefaultUnspecifiedEmbeddingIsAControlledSemanticOmission() {
        var legacy = insertReadableMemoryUsingDatabaseDefaultNormalization(
                "learner-vector-default", "legacy-default", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");

        assertThat(embeddingNormalization(legacy.versionId())).isEqualTo("UNSPECIFIED");
        assertThatThrownBy(() -> new VectorMemoryQuery(jdbcClient)
                .retrieve(query("learner-vector-default", vector(1.0f, 0.0f))))
                .isInstanceOf(RetrievalChannel.SemanticUnavailableException.class)
                .hasMessage("EMBEDDING_PROFILE_MISMATCH");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void directUnspecifiedQueryEmbeddingIsAControlledSemanticOmission() {
        var legacy = insertReadableMemoryUsingDatabaseDefaultNormalization(
                "learner-vector-direct", "legacy-direct", vector(1.0f, 0.0f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");
        var unspecified = new ResolveMemoryContextUseCase.QueryEmbedding(
                vectorValues(1.0f, 0.0f), "bge-m3", "v1", 1024, "UNSPECIFIED");

        assertThat(embeddingNormalization(legacy.versionId())).isEqualTo("UNSPECIFIED");
        assertThatThrownBy(() -> new VectorMemoryQuery(jdbcClient)
                .retrieve(authorizedQuery("learner-vector-direct", "legacy-direct", unspecified)))
                .isInstanceOf(RetrievalChannel.SemanticUnavailableException.class)
                .hasMessage("EMBEDDING_PROFILE_MISMATCH");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void explicitL2EmbeddingRemainsRetrievableWhenTheSameLearnerAlsoHasAnUnspecifiedEmbedding() {
        var explicit = insertReadableMemory("learner-vector-mixed", "explicit-l2", vector(1.0f, 0.0f),
                PrivacyLevel.STANDARD, NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");
        var unspecified = insertReadableMemoryUsingDatabaseDefaultNormalization(
                "learner-vector-mixed", "legacy-default", vector(0.8f, 0.6f), PrivacyLevel.STANDARD,
                NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE");

        var results = new VectorMemoryQuery(jdbcClient)
                .retrieve(query("learner-vector-mixed", vector(1.0f, 0.0f)));

        assertThat(embeddingNormalization(unspecified.versionId())).isEqualTo("UNSPECIFIED");
        assertThat(results).extracting(RetrievalChannel.RankedMemory::memoryId)
                .containsExactly(explicit.memoryId());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void recentEpisodeAdmissionStillReturnsTheVersionCompleteSourceClosure() {
        var memory = insertReadableMemory("learner-vector-a", "episode-source-closure", vector(1.0f, 0.0f),
                PrivacyLevel.STANDARD, NOW.minusSeconds(10), NOW.plusSeconds(10), true, "ACTIVE",
                SourceKind.EPISODE);
        appendGeneralSource("learner-vector-a", memory.versionId());

        var result = new RecentEpisodeQuery(jdbcClient)
                .retrieve(authorizedQuery("learner-vector-a", "episode-source-closure", null));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.memoryId()).isEqualTo(memory.memoryId());
            assertThat(item.sourceIds()).hasSize(2);
        });
    }

    private RetrievalChannel.AuthorizedMemoryQuery query(String subjectHash, String embedding) {
        return authorizedQuery(subjectHash, "preference", new ResolveMemoryContextUseCase.QueryEmbedding(
                vectorValues(1.0f, 0.0f), "bge-m3", "v1", 1024, "L2"));
    }

    private RetrievalChannel.AuthorizedMemoryQuery authorizedQuery(
            String subjectHash,
            String queryText,
            ResolveMemoryContextUseCase.QueryEmbedding embedding) {
        return new RetrievalChannel.AuthorizedMemoryQuery(
                subjectHash, "session-vector", NOW, Set.of(MemoryType.PREFERENCE), Set.of(),
                queryText, "TEACHING", embedding,
                8);
    }

    private StoredMemory insertReadableMemory(
            String subjectHash,
            String name,
            String embedding,
            PrivacyLevel privacy,
            Instant validFrom,
            Instant validUntil,
            boolean sourceClosed,
            String headStatus) {
        return insertReadableMemory(subjectHash, name, embedding, privacy, validFrom, validUntil, sourceClosed,
                headStatus, "L2", SourceKind.GENERAL);
    }

    private StoredMemory insertReadableMemory(
            String subjectHash,
            String name,
            String embedding,
            PrivacyLevel privacy,
            Instant validFrom,
            Instant validUntil,
            boolean sourceClosed,
            String headStatus,
            SourceKind sourceKind) {
        return insertReadableMemory(subjectHash, name, embedding, privacy, validFrom, validUntil, sourceClosed,
                headStatus, "L2", sourceKind);
    }

    private StoredMemory insertReadableMemory(
            String subjectHash,
            String name,
            String embedding,
            PrivacyLevel privacy,
            Instant validFrom,
            Instant validUntil,
            boolean sourceClosed,
            String headStatus,
            String normalization) {
        return insertReadableMemory(subjectHash, name, embedding, privacy, validFrom, validUntil, sourceClosed,
                headStatus, normalization, SourceKind.GENERAL);
    }

    private StoredMemory insertReadableMemoryUsingDatabaseDefaultNormalization(
            String subjectHash,
            String name,
            String embedding,
            PrivacyLevel privacy,
            Instant validFrom,
            Instant validUntil,
            boolean sourceClosed,
            String headStatus) {
        return insertReadableMemory(subjectHash, name, embedding, privacy, validFrom, validUntil, sourceClosed,
                headStatus, null, SourceKind.GENERAL);
    }

    private StoredMemory insertReadableMemory(
            String subjectHash,
            String name,
            String embedding,
            PrivacyLevel privacy,
            Instant validFrom,
            Instant validUntil,
            boolean sourceClosed,
            String headStatus,
            String normalization,
            SourceKind sourceKind) {
        var learnerId = learnerId(subjectHash);
        var assertionId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var protectedPrivacy = privacy == PrivacyLevel.SENSITIVE || privacy == PrivacyLevel.HIGH;
        jdbcClient.sql("INSERT INTO memory_assertion (memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at) VALUES (:id, :learnerId, :key, 'PREFERENCE', :now)")
                .param("id", assertionId).param("learnerId", learnerId).param("key", name).param("now", Timestamp.from(NOW)).update();
        jdbcClient.sql("""
                        INSERT INTO memory_version (memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                            status, value_json, valid_from, valid_until, recorded_at, recorded_until, confidence, stability_score,
                            privacy_level, consent_revision, created_at, protected_value_ciphertext,
                            protected_value_nonce, protected_value_key_reference, protected_value_algorithm,
                            protected_value_crypto_version)
                        VALUES (:id, :assertionId, :learnerId, 1, :status, CAST(:value AS jsonb), :validFrom, :validUntil,
                            :now, null, :confidence, :stability, :privacy, 3, :now, :ciphertext, :nonce,
                            :keyReference, :algorithm, :cryptoVersion)
                        """)
                .param("id", versionId).param("assertionId", assertionId).param("learnerId", learnerId)
                .param("status", headStatus).param("value", protectedPrivacy ? null : "{\"value\":\"" + name + "\"}")
                .param("validFrom", Timestamp.from(validFrom)).param("validUntil", Timestamp.from(validUntil))
                .param("now", Timestamp.from(NOW)).param("confidence", new BigDecimal("0.90"))
                .param("stability", new BigDecimal("0.80")).param("privacy", privacy.name())
                .param("ciphertext", protectedPrivacy ? new byte[16] : null)
                .param("nonce", protectedPrivacy ? new byte[12] : null)
                .param("keyReference", protectedPrivacy ? "k-" + "0".repeat(32) : null)
                .param("algorithm", protectedPrivacy ? "AES-256-GCM" : null)
                .param("cryptoVersion", protectedPrivacy ? 1 : null)
                .update();
        jdbcClient.sql("INSERT INTO memory_head_projection (memory_assertion_id, learner_subject_id, memory_version_id, status, memory_epoch, updated_at) VALUES (:assertionId, :learnerId, :versionId, :status, 1, :now)")
                .param("assertionId", assertionId).param("learnerId", learnerId).param("versionId", versionId)
                .param("status", headStatus).param("now", Timestamp.from(NOW)).update();
        var normalizationColumn = normalization == null ? "" : ", embedding_normalization";
        var normalizationValue = normalization == null ? "" : ", :normalization";
        var embeddingStatement = jdbcClient.sql("""
                        INSERT INTO memory_embedding (memory_embedding_id, memory_version_id, learner_subject_id, embedding,
                            embedding_profile, embedding_profile_version, embedding_dimension%s, content_digest, created_at)
                        VALUES (:id, :versionId, :learnerId, CAST(:embedding AS vector), 'bge-m3', 'v1', 1024%s, :digest, :now)
                        """.formatted(normalizationColumn, normalizationValue))
                .param("id", UUID.randomUUID()).param("versionId", versionId).param("learnerId", learnerId)
                .param("embedding", embedding).param("digest", "0".repeat(64)).param("now", Timestamp.from(NOW));
        if (normalization != null) {
            embeddingStatement = embeddingStatement.param("normalization", normalization);
        }
        embeddingStatement.update();
        if (sourceClosed) {
            jdbcClient.sql("INSERT INTO interaction_event (event_id, schema_version, learner_subject_id, session_id, event_type, source_kind, occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext, payload_digest, trace_id) VALUES (:eventId, 'v1', :learnerId, 'session-vector', 'PREFERENCE', :sourceKind, :now, :now, 'STANDARD', 3, :payload, :digest, 'trace-vector')")
                    .param("eventId", eventId).param("learnerId", learnerId).param("now", Timestamp.from(NOW))
                    .param("sourceKind", sourceKind.name())
                    .param("payload", "opaque".getBytes(StandardCharsets.UTF_8)).param("digest", "1".repeat(64)).update();
            jdbcClient.sql("INSERT INTO memory_source_link (memory_source_link_id, memory_version_id, learner_subject_id, source_event_id, source_schema_version, source_kind, created_at) VALUES (:id, :versionId, :learnerId, :eventId, 'v1', 'INTERACTION_EVENT', :now)")
                    .param("id", UUID.randomUUID()).param("versionId", versionId).param("learnerId", learnerId)
                    .param("eventId", eventId).param("now", Timestamp.from(NOW)).update();
        }
        return new StoredMemory(assertionId, versionId);
    }

    private static String vector(float first, float second) {
        return "[" + first + "," + second + "," + "0,".repeat(1021) + "0]";
    }

    private static List<Float> vectorValues(float first, float second) {
        var values = new ArrayList<Float>(1024);
        values.add(first);
        values.add(second);
        while (values.size() < 1024) {
            values.add(0.0f);
        }
        return List.copyOf(values);
    }

    private String embeddingNormalization(UUID versionId) {
        return jdbcClient.sql("SELECT embedding_normalization FROM memory_embedding WHERE memory_version_id = :versionId")
                .param("versionId", versionId).query(String.class).single();
    }

    private UUID learnerId(String subjectHash) {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:id, :subjectHash, 'ACTIVE', :now)
                        ON CONFLICT (subject_hash) DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("subjectHash", subjectHash).param("now", Timestamp.from(NOW))
                .update();
        return jdbcClient.sql("SELECT learner_subject_id FROM learner_subject WHERE subject_hash = :subjectHash")
                .param("subjectHash", subjectHash).query(UUID.class).single();
    }

    private void appendGeneralSource(String subjectHash, UUID versionId) {
        var learnerId = learnerId(subjectHash);
        var eventId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO interaction_event (event_id, schema_version, learner_subject_id, session_id, event_type, source_kind, occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext, payload_digest, trace_id) VALUES (:eventId, 'v1', :learnerId, 'session-vector', 'PREFERENCE', 'GENERAL', :now, :now, 'STANDARD', 3, :payload, :digest, 'trace-vector-extra')")
                .param("eventId", eventId).param("learnerId", learnerId).param("now", Timestamp.from(NOW))
                .param("payload", "opaque-extra".getBytes(StandardCharsets.UTF_8)).param("digest", "2".repeat(64)).update();
        jdbcClient.sql("INSERT INTO memory_source_link (memory_source_link_id, memory_version_id, learner_subject_id, source_event_id, source_schema_version, source_kind, created_at) VALUES (:id, :versionId, :learnerId, :eventId, 'v1', 'INTERACTION_EVENT', :now)")
                .param("id", UUID.randomUUID()).param("versionId", versionId).param("learnerId", learnerId)
                .param("eventId", eventId).param("now", Timestamp.from(NOW)).update();
    }

    private void clearAuthorityRows() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }

    private record StoredMemory(UUID memoryId, UUID versionId) {
    }
}
