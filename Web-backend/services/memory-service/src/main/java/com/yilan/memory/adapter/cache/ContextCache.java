package com.yilan.memory.adapter.cache;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.domain.identity.LearnerIdentity;

import java.util.Objects;
import java.util.Optional;

/**
 * Internal cache port for already bounded memory-context results. Implementations
 * must fail closed for cache use and always let PostgreSQL authority resolution continue.
 */
public interface ContextCache {

    Optional<ResolveMemoryContextUseCase.MemoryResolution> find(
            LearnerIdentity identity,
            ResolveMemoryContextUseCase.MemoryQuery query);

    void put(
            LearnerIdentity identity,
            ResolveMemoryContextUseCase.MemoryQuery query,
            ResolveMemoryContextUseCase.MemoryResolution resolution);

    /** Best-effort local-only invalidation after an authority gate closes. */
    default void evictL1ForSubject(String subjectHash) {
        Objects.requireNonNull(subjectHash, "subjectHash");
    }

    static ContextCache disabled() {
        return new ContextCache() {
            @Override
            public Optional<ResolveMemoryContextUseCase.MemoryResolution> find(
                    LearnerIdentity identity,
                    ResolveMemoryContextUseCase.MemoryQuery query) {
                Objects.requireNonNull(identity, "identity");
                Objects.requireNonNull(query, "query");
                return Optional.empty();
            }

            @Override
            public void put(
                    LearnerIdentity identity,
                    ResolveMemoryContextUseCase.MemoryQuery query,
                    ResolveMemoryContextUseCase.MemoryResolution resolution) {
                Objects.requireNonNull(identity, "identity");
                Objects.requireNonNull(query, "query");
                Objects.requireNonNull(resolution, "resolution");
            }
        };
    }
}
