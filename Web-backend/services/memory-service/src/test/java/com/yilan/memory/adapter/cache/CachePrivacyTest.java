package com.yilan.memory.adapter.cache;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.identity.LearnerIdentity;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CachePrivacyTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LearnerIdentity IDENTITY = new LearnerIdentity("private-subject", "session-1", 4);

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void currentHighPrivacyHeadFromPostgresDisablesTheAuthorityGate() {
        var subjectId = UUID.randomUUID();
        var assertionId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        try {
            jdbcClient.sql("INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at) "
                            + "VALUES (:id, :hash, 'ACTIVE', :now)")
                    .param("id", subjectId).param("hash", IDENTITY.subjectHash()).param("now", Timestamp.from(NOW)).update();
            jdbcClient.sql("INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at) "
                            + "VALUES (:id, 5, 7, :now)")
                    .param("id", subjectId).param("now", Timestamp.from(NOW)).update();
            jdbcClient.sql("INSERT INTO consent_policy_version "
                            + "(consent_policy_version_id, learner_subject_id, revision, status, allowed_categories, "
                            + "valid_from, valid_until, created_at) "
                            + "VALUES (:id, :subject, 4, 'ACTIVE', CAST('[\"PREFERENCE\"]' AS jsonb), :from, :until, :now)")
                    .param("id", UUID.randomUUID()).param("subject", subjectId)
                    .param("from", Timestamp.from(NOW.minus(Duration.ofHours(1))))
                    .param("until", Timestamp.from(NOW.plus(Duration.ofHours(1))))
                    .param("now", Timestamp.from(NOW)).update();
            jdbcClient.sql("INSERT INTO memory_assertion "
                            + "(memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at) "
                            + "VALUES (:id, :subject, 'cache-high', 'PREFERENCE', :now)")
                    .param("id", assertionId).param("subject", subjectId).param("now", Timestamp.from(NOW)).update();
            jdbcClient.sql("INSERT INTO memory_version "
                            + "(memory_version_id, memory_assertion_id, learner_subject_id, version_sequence, status, "
                            + "value_json, valid_from, valid_until, recorded_at, recorded_until, confidence, stability_score, "
                            + "privacy_level, consent_revision, created_at, protected_value_ciphertext, "
                            + "protected_value_nonce, protected_value_key_reference, protected_value_algorithm, "
                            + "protected_value_crypto_version) "
                            + "VALUES (:id, :assertion, :subject, 1, 'ACTIVE', NULL, "
                            + ":from, :until, :now, NULL, 0.9, 0.8, 'HIGH', 4, :now, :ciphertext, :nonce, :keyReference, "
                            + "'AES-256-GCM', 1)")
                    .param("id", versionId).param("assertion", assertionId).param("subject", subjectId)
                    .param("from", Timestamp.from(NOW.minus(Duration.ofHours(1))))
                    .param("until", Timestamp.from(NOW.plus(Duration.ofHours(1))))
                    .param("now", Timestamp.from(NOW)).param("ciphertext", new byte[16]).param("nonce", new byte[12])
                    .param("keyReference", "k-" + "0".repeat(32)).update();
            jdbcClient.sql("INSERT INTO memory_head_projection "
                            + "(memory_assertion_id, learner_subject_id, memory_version_id, status, memory_epoch, updated_at) "
                            + "VALUES (:assertion, :subject, :version, 'ACTIVE', 5, :now)")
                    .param("assertion", assertionId).param("subject", subjectId).param("version", versionId)
                    .param("now", Timestamp.from(NOW)).update();

            var gate = new PostgresCacheAuthorityGate(jdbcClient,
                    new ContextCacheKeyFactory("test-only-cache-key"), CLOCK);

            assertThat(gate.eligibleKey(IDENTITY, query(false))).isEmpty();
        } finally {
            // This is the ephemeral Testcontainers authority database; append-only rows
            // cannot be deleted one-by-one, so use the established test-suite cleanup.
            jdbcClient.sql("TRUNCATE TABLE learner_subject CASCADE").update();
        }
    }

    @Test
    void highPrivacyRequestNeverTouchesEitherCacheTier() {
        var l1 = new CaffeineContextCache(Duration.ofMinutes(1), 8);
        var backend = new RecordingBackend();
        var cache = cache(new PostgresCacheAuthorityGate.AuthoritySnapshot(5, 7, true, true),
                new ContextCacheKeyFactory("test-only-cache-key"), l1, backend);

        cache.put(IDENTITY, query(false), emptyResolution());
        assertThat(cache.find(IDENTITY, query(false))).isEmpty();

        assertThat(l1.requestCount()).isZero();
        assertThat(backend.touches).isZero();
    }

    @Test
    void graphRequiredRequestNeverTouchesEitherCacheTier() {
        var l1 = new CaffeineContextCache(Duration.ofMinutes(1), 8);
        var backend = new RecordingBackend();
        var cache = cache(new PostgresCacheAuthorityGate.AuthoritySnapshot(5, 7, true, false),
                new ContextCacheKeyFactory("test-only-cache-key"), l1, backend);

        cache.put(IDENTITY, query(true), emptyResolution());
        assertThat(cache.find(IDENTITY, query(true))).isEmpty();

        assertThat(l1.requestCount()).isZero();
        assertThat(backend.touches).isZero();
    }

    @Test
    void absentHmacSecretDisablesCachingWithoutTouchingEitherTier() {
        var l1 = new CaffeineContextCache(Duration.ofMinutes(1), 8);
        var backend = new RecordingBackend();
        var cache = cache(new PostgresCacheAuthorityGate.AuthoritySnapshot(5, 7, true, false),
                new ContextCacheKeyFactory(""), l1, backend);

        cache.put(IDENTITY, query(false), emptyResolution());
        assertThat(cache.find(IDENTITY, query(false))).isEmpty();

        assertThat(l1.requestCount()).isZero();
        assertThat(backend.touches).isZero();
    }

    @Test
    void opaqueKeyBindsEveryResolutionSemanticInputWithoutExposingRawInputs() {
        var keyFactory = new ContextCacheKeyFactory("test-only-cache-key");
        var identity = new LearnerIdentity("cache-subject", "session-private", 4);
        var embedding = embedding("model-a", "2026.07", "L2", 1024, 0.03125f);
        var baseline = query("TEACHING", Set.of(MemoryType.PREFERENCE, MemoryType.MASTERY),
                Set.of(MemoryType.PREFERENCE), 8, 900, embedding);
        var baselineKey = key(keyFactory, identity, baseline);

        var variants = List.of(
                key(keyFactory, new LearnerIdentity("cache-subject", "session-other", 4), baseline),
                key(keyFactory, identity, query("ASSESSMENT", baseline.allowedTypes(), baseline.requiredTypes(),
                        8, 900, embedding)),
                key(keyFactory, identity, query("TEACHING", Set.of(MemoryType.PREFERENCE),
                        Set.of(MemoryType.PREFERENCE), 8, 900, embedding)),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), Set.of(MemoryType.MASTERY),
                        8, 900, embedding)),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), baseline.requiredTypes(),
                        7, 900, embedding)),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), baseline.requiredTypes(),
                        8, 899, embedding)),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), baseline.requiredTypes(),
                        8, 900, null)),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), baseline.requiredTypes(),
                        8, 900, embedding("model-b", "2026.07", "L2", 1024, 0.03125f))),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), baseline.requiredTypes(),
                        8, 900, embedding("model-a", "2026.08", "L2", 1024, 0.03125f))),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), baseline.requiredTypes(),
                        8, 900, embedding("model-a", "2026.07", "NONE", 1024, 0.03125f))),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), baseline.requiredTypes(),
                        8, 900, embedding("model-a", "2026.07", "L2", 1023, 0.03125f))),
                key(keyFactory, identity, query("TEACHING", baseline.allowedTypes(), baseline.requiredTypes(),
                        8, 900, embedding("model-a", "2026.07", "L2", 1024, 0.03124f))));

        assertThat(variants).doesNotContain(baselineKey);
        assertThat(variants.stream().collect(Collectors.toSet())).hasSize(variants.size());
        assertThat(baselineKey).matches("memory:ctx:[0-9a-f]{64}");
        assertThat(baselineKey)
                .doesNotContain("cache-subject", "session-private", "explain clearly", "model-a", "2026.07", "0.03125");
    }

    private static TieredContextCache cache(
            PostgresCacheAuthorityGate.AuthoritySnapshot snapshot,
            ContextCacheKeyFactory keyFactory,
            CaffeineContextCache l1,
            RedisContextCache.Backend backend) {
        var current = new AtomicReference<>(snapshot);
        return new TieredContextCache(
                new PostgresCacheAuthorityGate((subject, types, asOf) -> Optional.of(current.get()), keyFactory, CLOCK),
                l1, new RedisContextCache(backend, CLOCK));
    }

    private static ResolveMemoryContextUseCase.MemoryQuery query(boolean graphRequired) {
        return new ResolveMemoryContextUseCase.MemoryQuery(
                "explain clearly", "TEACHING", java.util.Set.of(MemoryType.PREFERENCE), java.util.Set.of(),
                8, 900, null, Duration.ofMillis(20), graphRequired,
                new ResolveMemoryContextUseCase.CacheScope(
                        false, "", "", "", List.of(), "v1"));
    }

    private static ResolveMemoryContextUseCase.MemoryQuery query(
            String taskType,
            Set<MemoryType> allowedTypes,
            Set<MemoryType> requiredTypes,
            int maxItems,
            int maxEstimatedTokens,
            ResolveMemoryContextUseCase.QueryEmbedding embedding) {
        return new ResolveMemoryContextUseCase.MemoryQuery(
                "explain clearly", taskType, allowedTypes, requiredTypes, maxItems, maxEstimatedTokens, embedding,
                Duration.ofMillis(20), false,
                new ResolveMemoryContextUseCase.CacheScope(
                        false, "", "", "", List.of(), "v1"));
    }

    private static ResolveMemoryContextUseCase.QueryEmbedding embedding(
            String modelId,
            String modelVersion,
            String normalization,
            int dimension,
            float value) {
        return new ResolveMemoryContextUseCase.QueryEmbedding(
                java.util.Collections.nCopies(1024, value), modelId, modelVersion, dimension, normalization);
    }

    private static String key(
            ContextCacheKeyFactory keyFactory,
            LearnerIdentity identity,
            ResolveMemoryContextUseCase.MemoryQuery query) {
        return keyFactory.create(identity, query, 5, 7).orElseThrow();
    }

    private static ResolveMemoryContextUseCase.MemoryResolution emptyResolution() {
        return new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.EMPTY, List.of(), List.of(), null);
    }

    private static final class RecordingBackend implements RedisContextCache.Backend {
        private int touches;
        @Override public Optional<RedisContextCache.Entry> get(String key) { touches++; return Optional.empty(); }
        @Override public void put(String key, RedisContextCache.Entry entry, Duration ttl) { touches++; }
    }
}
