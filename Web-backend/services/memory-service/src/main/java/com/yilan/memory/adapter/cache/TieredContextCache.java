package com.yilan.memory.adapter.cache;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.domain.identity.LearnerIdentity;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Epoch-bound best-effort cache. It never decides authority eligibility and it
 * cannot turn a Redis failure into a changed memory-resolution result.
 */
public final class TieredContextCache implements ContextCache {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final int MAX_VALUE_BYTES = 16_384;

    private final PostgresCacheAuthorityGate authorityGate;
    private final CaffeineContextCache l1;
    private final RedisContextCache l2;

    public TieredContextCache(
            PostgresCacheAuthorityGate authorityGate,
            CaffeineContextCache l1,
            RedisContextCache l2) {
        this.authorityGate = Objects.requireNonNull(authorityGate, "authorityGate");
        this.l1 = Objects.requireNonNull(l1, "l1");
        this.l2 = Objects.requireNonNull(l2, "l2");
    }

    @Override
    public Optional<ResolveMemoryContextUseCase.MemoryResolution> find(
            LearnerIdentity identity,
            ResolveMemoryContextUseCase.MemoryQuery query) {
        var l1Key = authorityGate.eligibleKey(identity, query);
        if (l1Key.isEmpty()) {
            return Optional.empty();
        }
        var l1Result = l1.find(l1Key.orElseThrow());
        if (l1Result.filter(result -> cacheable(query, result)).isPresent()) {
            return l1Result;
        }

        var l2Key = authorityGate.eligibleKey(identity, query);
        if (l2Key.isEmpty()) {
            return Optional.empty();
        }
        var l2Result = l2.find(l2Key.orElseThrow());
        if (l2Result.isEmpty()) {
            return Optional.empty();
        }
        if (!cacheable(query, l2Result.orElseThrow())) {
            return Optional.empty();
        }

        var warmKey = authorityGate.eligibleKey(identity, query);
        if (warmKey.filter(l2Key.orElseThrow()::equals).isPresent()) {
            l1.put(warmKey.orElseThrow(), l2Result.orElseThrow());
        }
        return l2Result;
    }

    @Override
    public void put(
            LearnerIdentity identity,
            ResolveMemoryContextUseCase.MemoryQuery query,
            ResolveMemoryContextUseCase.MemoryResolution resolution) {
        var boundedEntry = boundedEntry(query, resolution);
        if (boundedEntry.isEmpty()) {
            return;
        }
        var l1Key = authorityGate.eligibleKey(identity, query);
        if (l1Key.isEmpty()) {
            return;
        }
        l1.put(l1Key.orElseThrow(), resolution);

        var l2Key = authorityGate.eligibleKey(identity, query);
        if (l2Key.isEmpty()) {
            return;
        }
        l2.put(l2Key.orElseThrow(), boundedEntry.orElseThrow(), TTL);
    }

    /**
     * There is no safe subject-to-key index in L1. A policy revocation
     * therefore invalidates the bounded local tier in full before success;
     * L2 remains epoch-gated by PostgreSQL and is never an authority.
     */
    public void evictL1ForSubject(String subjectHash) {
        if (subjectHash == null || subjectHash.isBlank()) {
            throw new IllegalArgumentException("subjectHash");
        }
        l1.invalidateAll();
    }

    private Optional<RedisContextCache.Entry> boundedEntry(
            ResolveMemoryContextUseCase.MemoryQuery query,
            ResolveMemoryContextUseCase.MemoryResolution resolution) {
        if (!cacheableBasics(query, resolution)) {
            return Optional.empty();
        }
        return l2.boundedEntry(resolution, TTL, MAX_VALUE_BYTES);
    }

    private boolean cacheable(
            ResolveMemoryContextUseCase.MemoryQuery query,
            ResolveMemoryContextUseCase.MemoryResolution resolution) {
        return boundedEntry(query, resolution).isPresent();
    }

    private static boolean cacheableBasics(
            ResolveMemoryContextUseCase.MemoryQuery query,
            ResolveMemoryContextUseCase.MemoryResolution resolution) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(resolution, "resolution");
        if (query.graphRequired()
                || resolution.status() == ResolveMemoryContextUseCase.ResolutionStatus.DEGRADED
                || !resolution.graphHints().isEmpty()
                || resolution.items().stream().anyMatch(item -> item.privacyLevel()
                        == com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel.SENSITIVE
                        || item.privacyLevel() == com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel.HIGH)
                || resolution.items().size() > query.maxItems()) {
            return false;
        }
        var totalTokens = resolution.items().stream().mapToInt(item -> item.estimatedTokens()).sum();
        if (totalTokens > query.maxEstimatedTokens()) {
            return false;
        }
        return true;
    }
}
