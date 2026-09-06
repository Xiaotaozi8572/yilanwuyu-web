package com.yilan.memory.application.consent;

import com.yilan.memory.adapter.cache.CaffeineContextCache;
import com.yilan.memory.adapter.cache.ContextCacheKeyFactory;
import com.yilan.memory.adapter.cache.PostgresCacheAuthorityGate;
import com.yilan.memory.adapter.cache.RedisContextCache;
import com.yilan.memory.adapter.cache.TieredContextCache;
import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.domain.identity.LearnerIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEpochInvalidationTest {

    @Test
    void withdrawalReturnsOnlyAfterL1EvictionAndEpochCommit() {
        var clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
        var l1 = new CaffeineContextCache(Duration.ofMinutes(1), 8);
        var cache = new TieredContextCache(
                new PostgresCacheAuthorityGate(
                        (subject, types, asOf) -> Optional.of(new PostgresCacheAuthorityGate.AuthoritySnapshot(3, 7, true, false)),
                        new ContextCacheKeyFactory("test-only-cache-key"), clock),
                l1, new RedisContextCache((RedisContextCache.Backend) new NoOpBackend(), clock));
        var identity = new LearnerIdentity("learner-a", "session-a", 1);
        var query = new ResolveMemoryContextUseCase.MemoryQuery(
                "q", "task", Set.of(), Set.of(), 1, 1, null, Duration.ZERO, false);

        l1.put("stale-before-withdrawal", new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.EMPTY, List.of(), List.of(), null));
        new PolicyEpochService(cache::evictL1ForSubject).invalidateAfterAuthorityCommit(identity.subjectHash(), 8);

        assertThat(l1.find("stale-before-withdrawal")).isEmpty();
    }

    private static final class NoOpBackend implements RedisContextCache.Backend {
        @Override public Optional<RedisContextCache.Entry> get(String key) { return Optional.empty(); }
        @Override public void put(String key, RedisContextCache.Entry entry, Duration ttl) { }
    }
}
