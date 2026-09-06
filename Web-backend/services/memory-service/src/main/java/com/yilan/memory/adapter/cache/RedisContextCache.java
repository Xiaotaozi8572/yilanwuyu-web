package com.yilan.memory.adapter.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Best-effort Redis L2 wrapper. Backend errors are intentionally converted to
 * cache misses so Redis cannot alter an authority result or status.
 */
public final class RedisContextCache {

    private final Backend backend;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RedisContextCache(Backend backend, Clock clock) {
        this(backend, fallbackObjectMapper(), clock);
    }

    public RedisContextCache(Backend backend, ObjectMapper objectMapper, Clock clock) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RedisContextCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock) {
        this(new SpringBackend(redisTemplate, objectMapper), objectMapper, clock);
    }

    public Optional<ResolveMemoryContextUseCase.MemoryResolution> find(String key) {
        try {
            return backend.get(Objects.requireNonNull(key, "key"))
                    .filter(entry -> entry.expiresAt().isAfter(Instant.now(clock)))
                    .map(Entry::resolution);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public void put(String key, ResolveMemoryContextUseCase.MemoryResolution resolution, Duration ttl) {
        boundedEntry(resolution, ttl, Integer.MAX_VALUE)
                .ifPresent(entry -> put(key, entry, ttl));
    }

    /**
     * Produces the exact entry representation Spring Redis writes and rejects
     * values that cannot be serialized or exceed the supplied UTF-8 JSON cap.
     */
    Optional<Entry> boundedEntry(
            ResolveMemoryContextUseCase.MemoryResolution resolution,
            Duration ttl,
            int maximumBytes) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative() || maximumBytes < 1) {
            throw new IllegalArgumentException("cache bounds");
        }
        var entry = new Entry(resolution, Instant.now(clock).plus(ttl));
        try {
            return objectMapper.writeValueAsBytes(entry).length <= maximumBytes
                    ? Optional.of(entry)
                    : Optional.empty();
        } catch (JsonProcessingException | RuntimeException serializationFailure) {
            return Optional.empty();
        }
    }

    void put(String key, Entry entry, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl");
        }
        try {
            backend.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(entry, "entry"), ttl);
        } catch (RuntimeException ignored) {
            // A cache write must never replace a PostgreSQL authority result.
        }
    }

    public interface Backend {
        Optional<Entry> get(String key);
        void put(String key, Entry entry, Duration ttl);
    }

    public record Entry(ResolveMemoryContextUseCase.MemoryResolution resolution, Instant expiresAt) {
        public Entry {
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private static ObjectMapper fallbackObjectMapper() {
        var module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeString(value.toString());
            }
        });
        return new ObjectMapper().registerModule(module);
    }

    private static final class SpringBackend implements Backend {
        private final StringRedisTemplate redisTemplate;
        private final ObjectMapper objectMapper;

        private SpringBackend(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
            this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        }

        @Override
        public Optional<Entry> get(String key) {
            var serialized = redisTemplate.opsForValue().get(key);
            if (serialized == null || serialized.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(objectMapper.readValue(serialized, Entry.class));
            } catch (JsonProcessingException invalidValue) {
                return Optional.empty();
            }
        }

        @Override
        public void put(String key, Entry entry, Duration ttl) {
            try {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), ttl);
            } catch (JsonProcessingException serializationFailure) {
                throw new IllegalArgumentException("unserializable bounded memory context", serializationFailure);
            }
        }
    }
}
