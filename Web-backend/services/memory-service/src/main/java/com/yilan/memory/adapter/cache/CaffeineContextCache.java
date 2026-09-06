package com.yilan.memory.adapter.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.yilan.memory.application.context.ResolveMemoryContextUseCase;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Bounded process-local L1; it is never a long-term authority. */
public final class CaffeineContextCache {

    private final Cache<String, ResolveMemoryContextUseCase.MemoryResolution> entries;

    public CaffeineContextCache(Duration ttl, long maximumItems) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative() || maximumItems < 1) {
            throw new IllegalArgumentException("cache bounds");
        }
        this.entries = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumItems)
                .recordStats()
                .build();
    }

    public Optional<ResolveMemoryContextUseCase.MemoryResolution> find(String key) {
        return Optional.ofNullable(entries.getIfPresent(Objects.requireNonNull(key, "key")));
    }

    public void put(String key, ResolveMemoryContextUseCase.MemoryResolution value) {
        entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    /** Consent withdrawal uses full local invalidation until a safe subject index exists. */
    public void invalidateAll() {
        entries.invalidateAll();
        entries.cleanUp();
    }

    public long requestCount() {
        return entries.stats().requestCount();
    }

    public CacheStats stats() {
        return entries.stats();
    }
}
