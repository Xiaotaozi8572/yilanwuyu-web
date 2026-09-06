package com.yilan.memory.adapter.cache;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.identity.LearnerIdentity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TieredContextCacheTest {

    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LearnerIdentity IDENTITY = new LearnerIdentity("cache-subject", "session-1", 4);

    @Test
    void epochChangeMakesBothCacheTiersUnusable() {
        var snapshot = new AtomicReference<>(snapshot(5, 7, false));
        var l2 = new RecordingBackend();
        var cache = cache((subject, types, asOf) -> Optional.of(snapshot.get()), "test-only-cache-key", l2);
        var query = query(false);
        var value = emptyResolution();

        cache.put(IDENTITY, query, value);
        assertThat(cache.find(IDENTITY, query)).contains(value);

        snapshot.set(snapshot(6, 7, false));

        assertThat(cache.find(IDENTITY, query)).isEmpty();
        assertThat(l2.keys()).hasSize(1);
    }

    @Test
    void revokedAuthorityConsentMakesSurvivingL1AndRedisEntriesUnusable() {
        var snapshot = new AtomicReference<>(snapshot(5, 7, false));
        var l2 = new RecordingBackend();
        var cache = cache((subject, types, asOf) -> Optional.of(snapshot.get()), "test-only-cache-key", l2);
        var query = query(false);
        var value = emptyResolution();

        cache.put(IDENTITY, query, value);
        assertThat(cache.find(IDENTITY, query)).contains(value);

        snapshot.set(new PostgresCacheAuthorityGate.AuthoritySnapshot(5, 7, false, false));

        assertThat(cache.find(IDENTITY, query)).isEmpty();
        assertThat(l2.keys()).hasSize(1);
    }

    @Test
    void redisLossFallsThroughToPostgresAuthorityResultWithoutChangingStatus() {
        var snapshot = new AtomicReference<>(snapshot(5, 7, false));
        var cache = cache((subject, types, asOf) -> Optional.of(snapshot.get()),
                "test-only-cache-key", new FailingBackend());
        var channelCalls = new AtomicInteger();
        var channel = new RetrievalChannel() {
            @Override public String name() { return "structured"; }
            @Override public List<RankedMemory> retrieve(AuthorizedMemoryQuery ignored) {
                channelCalls.incrementAndGet();
                return List.of(memory());
            }
        };
        var useCase = new ResolveMemoryContextUseCase(
                properties(), subject -> Optional.of(activePolicy()), CLOCK, List.of(channel),
                ResolveMemoryContextUseCase.GraphHintReader.disabled(), cache);

        var result = useCase.resolve(IDENTITY, query(false));

        assertThat(result.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.APPLIED);
        assertThat(result.items()).singleElement();
        assertThat(channelCalls).hasValue(1);
    }

    @Test
    void unusableCachedValuesAreMissesAndNeverWarmL1() {
        var backend = new RecordingBackend();
        var l1 = new CaffeineContextCache(Duration.ofMinutes(1), 8);
        var gate = new PostgresCacheAuthorityGate(
                (subject, types, asOf) -> Optional.of(snapshot(5, 7, false)),
                new ContextCacheKeyFactory("test-only-cache-key"), CLOCK);
        var cache = new TieredContextCache(gate, l1, new RedisContextCache(backend, CLOCK));
        var query = query(false);
        var cacheKey = gate.eligibleKey(IDENTITY, query).orElseThrow();

        for (var invalid : List.of(degradedResolution(), resolutionWithGraphHint(), resolutionWithTooManyItems(),
                resolutionWithTooManyTokens(), resolutionWithEscapingHeavyValue())) {
            l1.put(cacheKey, invalid);
            assertThat(cache.find(IDENTITY, query)).as("L1 value %s", invalid.status()).isEmpty();

            var l2Only = new CaffeineContextCache(Duration.ofMinutes(1), 8);
            backend.entries.put(cacheKey, new RedisContextCache.Entry(invalid, NOW.plusSeconds(20)));
            var l2Cache = new TieredContextCache(gate, l2Only, new RedisContextCache(backend, CLOCK));
            assertThat(l2Cache.find(IDENTITY, query)).as("L2 value %s", invalid.status()).isEmpty();
            assertThat(l2Only.find(cacheKey)).isEmpty();
            backend.entries.clear();
        }

        backend.entries.put(cacheKey, new RedisContextCache.Entry(emptyResolution(), NOW.minusMillis(1)));
        var l2Only = new CaffeineContextCache(Duration.ofMinutes(1), 8);
        var staleCache = new TieredContextCache(gate, l2Only, new RedisContextCache(backend, CLOCK));
        assertThat(staleCache.find(IDENTITY, query)).isEmpty();
        assertThat(l2Only.find(cacheKey)).isEmpty();
    }

    @Test
    void jsonEscapingExpansionIsRejectedBeforeEitherCacheTierWrite() {
        var backend = new RecordingBackend();
        var l1 = new CaffeineContextCache(Duration.ofMinutes(1), 8);
        var cache = cache((subject, types, asOf) -> Optional.of(snapshot(5, 7, false)),
                "test-only-cache-key", backend, l1);
        var query = query(false);
        var gate = new PostgresCacheAuthorityGate(
                (subject, types, asOf) -> Optional.of(snapshot(5, 7, false)),
                new ContextCacheKeyFactory("test-only-cache-key"), CLOCK);
        var cacheKey = gate.eligibleKey(IDENTITY, query).orElseThrow();

        cache.put(IDENTITY, query, resolutionWithEscapingHeavyValue());

        assertThat(l1.find(cacheKey)).isEmpty();
        assertThat(backend.keys()).isEmpty();
    }

    @Test
    void sensitiveResolutionNeverEntersEitherTierAndUnsafeExistingEntriesAreMisses() {
        var backend = new RecordingBackend();
        var l1 = new CaffeineContextCache(Duration.ofMinutes(1), 8);
        var keyFactory = new ContextCacheKeyFactory("test-only-cache-key");
        var cache = cache((subject, types, asOf) -> Optional.of(snapshot(5, 7, false)),
                "test-only-cache-key", backend, l1);
        var query = query(false);
        var cacheKey = keyFactory.create(IDENTITY, query, 5, 7).orElseThrow();
        var sensitive = new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.APPLIED,
                List.of(memory().withPrivacy(PrivacyLevel.SENSITIVE)), List.of(), null);

        cache.put(IDENTITY, query, sensitive);

        assertThat(l1.find(cacheKey)).isEmpty();
        assertThat(backend.keys()).isEmpty();

        l1.put(cacheKey, sensitive);
        backend.entries.put(cacheKey, new RedisContextCache.Entry(sensitive, NOW.plusSeconds(20)));

        assertThat(cache.find(IDENTITY, query)).isEmpty();
    }

    private static TieredContextCache cache(
            PostgresCacheAuthorityGate.AuthorityReader reader,
            String secret,
            RedisContextCache.Backend backend) {
        return cache(reader, secret, backend, new CaffeineContextCache(Duration.ofMinutes(1), 8));
    }

    private static TieredContextCache cache(
            PostgresCacheAuthorityGate.AuthorityReader reader,
            String secret,
            RedisContextCache.Backend backend,
            CaffeineContextCache l1) {
        return new TieredContextCache(
                new PostgresCacheAuthorityGate(reader, new ContextCacheKeyFactory(secret), CLOCK),
                l1,
                new RedisContextCache(backend, CLOCK));
    }

    private static ResolveMemoryContextUseCase.MemoryQuery query(boolean graphRequired) {
        return new ResolveMemoryContextUseCase.MemoryQuery(
                "explain clearly", "TEACHING", Set.of(MemoryType.PREFERENCE), Set.of(), 8, 900, null,
                Duration.ofMillis(20), graphRequired,
                new ResolveMemoryContextUseCase.CacheScope(
                        true, "lesson", "lesson-1", "zh-CN", List.of("object-b", "object-a"), "v1"));
    }

    private static PostgresCacheAuthorityGate.AuthoritySnapshot snapshot(
            long memoryEpoch, long consentEpoch, boolean highPrivacy) {
        return new PostgresCacheAuthorityGate.AuthoritySnapshot(memoryEpoch, consentEpoch, true, highPrivacy);
    }

    private static ConsentPolicy activePolicy() {
        return new ConsentPolicy(ConsentPolicy.SubjectStatus.ACTIVE, ConsentPolicy.PolicyStatus.ACTIVE, 4,
                Set.of(MemoryCategory.PREFERENCE), Instant.EPOCH, NOW.plus(Duration.ofDays(1)));
    }

    private static MemoryProperties properties() {
        var rules = new EnumMap<MemoryType, MemoryProperties.TypeRule>(MemoryType.class);
        for (var type : MemoryType.values()) {
            rules.put(type, new MemoryProperties.TypeRule(1, new BigDecimal("0.70"), Duration.ofDays(30)));
        }
        return new MemoryProperties("rules-cache-v1", rules,
                new MemoryProperties.RetrievalWeights(new BigDecimal("0.30"), new BigDecimal("0.20"),
                        new BigDecimal("0.50"), new BigDecimal("0.10")),
                60, new MemoryProperties.Budgets(8, 900));
    }

    private static ResolveMemoryContextUseCase.MemoryResolution degradedResolution() {
        return new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.DEGRADED, List.of(), List.of(), "CHANNEL_FAILURE");
    }

    private static ResolveMemoryContextUseCase.MemoryResolution resolutionWithGraphHint() {
        return new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.APPLIED, List.of(), List.of(), null,
                List.of(new ResolveMemoryContextUseCase.GraphHint(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        NOW.minus(Duration.ofMinutes(1)), NOW.plus(Duration.ofMinutes(1)))));
    }

    private static ResolveMemoryContextUseCase.MemoryResolution resolutionWithTooManyItems() {
        return new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.APPLIED,
                java.util.Collections.nCopies(9, memory()), List.of(), null);
    }

    private static ResolveMemoryContextUseCase.MemoryResolution resolutionWithTooManyTokens() {
        return new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.APPLIED, List.of(memory(901, "{\"answer_style\":\"concise\"}")),
                List.of(), null);
    }

    private static ResolveMemoryContextUseCase.MemoryResolution resolutionWithEscapingHeavyValue() {
        return new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.APPLIED,
                List.of(memory(1, "\"".repeat(9_000))), List.of(), null);
    }

    private static RetrievalChannel.RankedMemory memory() {
        return memory(80, "{\"answer_style\":\"concise\"}");
    }

    private static RetrievalChannel.RankedMemory memory(int estimatedTokens, String valueJson) {
        return new RetrievalChannel.RankedMemory(
                UUID.randomUUID(), UUID.randomUUID(), MemoryType.PREFERENCE, valueJson,
                new BigDecimal("0.90"), new BigDecimal("0.80"), NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(1)), NOW.minus(Duration.ofHours(1)), null, estimatedTokens, true,
                PrivacyLevel.STANDARD, "ACTIVE", "CONFIRMED", List.of("event-1"), null,
                new BigDecimal("0.50"), RetrievalChannel.UseClass.OPTIONAL);
    }

    private static ResolveMemoryContextUseCase.MemoryResolution emptyResolution() {
        return new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.EMPTY, List.of(), List.of(), null);
    }

    private static final class RecordingBackend implements RedisContextCache.Backend {
        private final Map<String, RedisContextCache.Entry> entries = new java.util.HashMap<>();
        @Override public Optional<RedisContextCache.Entry> get(String key) { return Optional.ofNullable(entries.get(key)); }
        @Override public void put(String key, RedisContextCache.Entry entry, Duration ignored) { entries.put(key, entry); }
        Set<String> keys() { return entries.keySet(); }
    }

    private static final class FailingBackend implements RedisContextCache.Backend {
        @Override public Optional<RedisContextCache.Entry> get(String key) { throw new IllegalStateException("redis unavailable"); }
        @Override public void put(String key, RedisContextCache.Entry entry, Duration ttl) { throw new IllegalStateException("redis unavailable"); }
    }
}
